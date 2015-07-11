;; Copyright (c) 2015 Andrey Antukh <niwi@niwi.nz>
;; All rights reserved.
;;
;; Redistribution and use in source and binary forms, with or without
;; modification, are permitted provided that the following conditions
;; are met:
;;
;; 1. Redistributions of source code must retain the above copyright
;;    notice, this list of conditions and the following disclaimer.
;; 2. Redistributions in binary form must reproduce the above copyright
;;    notice, this list of conditions and the following disclaimer in the
;;    documentation and/or other materials provided with the distribution.
;;
;; THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
;; IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
;; OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
;; IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
;; INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
;; NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
;; DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
;; THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
;; (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
;; THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

(ns promissum.core
  "A promise implementation for Clojure that uses jdk8
  completable futures behind the scenes."
  (:refer-clojure :exclude [future promise deliver])
  (:require [cats.core :as m]
            [cats.protocols :as cats]
            [promissum.protocols :as proto]
            [manifold.deferred :as md])
  (:import java.util.concurrent.CompletableFuture
           java.util.concurrent.CompletionStage
           java.util.concurrent.TimeoutException
           java.util.concurrent.ExecutionException
           java.util.concurrent.CompletionException
           java.util.concurrent.TimeUnit
           java.util.concurrent.Executor
           java.util.concurrent.ForkJoinPool
           java.util.function.Function))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Concurrency
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^{:doc "The main executor service for schedule promises."
       :dynamic true}
  *executor* (ForkJoinPool/commonPool))

(defn- function
  "Given an plain function `f`, return an
  instace of the java.util.concurrent.Function
  class."
  [f]
  (reify Function
    (apply [_ v] (f v))))

(defn- schedule
  "Schedule a functon to execute in
  a provided executor service."
  ([func] (schedule *executor* func))
  ([^Executor executor ^Runnable func]
   (.execute executor func)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Monad type implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare promise*)

(def ^{:no-doc true}
  promise-monad
  (reify
    cats/Functor
    (fmap [mn f mv]
      (proto/then mv f))

    cats/Monad
    (mreturn [_ v]
      (promise* v))

    (mbind [mn mv f]
      (let [ctx m/*context*]
        (proto/then mv (fn [v]
                         (m/with-monad ctx
                           (f v))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare impl-extract)
(declare impl-deref)
(declare impl-deref-await)
(declare impl-then)
(declare impl-error)
(declare impl-deliver)

(deftype Promise [^CompletionStage cf]
  cats/Context
  (get-context [_]
    promise-monad)

  cats/Extract
  (extract [_]
    (impl-extract cf))

  clojure.lang.IDeref
  (deref [_]
    (impl-deref cf))

  clojure.lang.IPending
  (isRealized [_]
    (.isDone cf))

  clojure.lang.IBlockingDeref
  (deref [_ ^long ms defaultvalue]
    (impl-deref-await cf ms defaultvalue))

  proto/IState
  (rejected? [_]
    (.isCompletedExceptionally cf))

  (resolved? [_]
    (and (not (.isCompletedExceptionally cf))
         (not (.isCancelled cf))
         (.isDone cf)))

  (done? [_]
    (.isDone cf))

  proto/IPromise
  (then [_ callback]
    (impl-then cf callback))

  (error [_ callback]
    (impl-error cf callback))

  (deliver [_ value]
    (impl-deliver cf value)))

(defn- impl-deref
  [^CompletionStage cf]
  (try
    (.get cf)
    (catch ExecutionException e
      (let [e' (.getCause e)]
        (.setStackTrace e' (.getStackTrace e))
        (throw e')))
    (catch CompletionException e
      (let [e' (.getCause e)]
        (.setStackTrace e' (.getStackTrace e))
        (throw e')))))

(defn- impl-deref-await
  [^CompletionStage cf ^long ms default]
  (try
    (.get cf ms TimeUnit/SECONDS)
    (catch TimeoutException e
      default)
    (catch ExecutionException e
      (let [e' (.getCause e)]
        (.setStackTrace e' (.getStackTrace e))
        (throw e')))
    (catch CompletionException e
      (let [e' (.getCause e)]
        (.setStackTrace e' (.getStackTrace e))
        (throw e')))))

(defn- impl-extract
  [^CompletionStage cf]
  (try
    (.getNow cf nil)
    (catch ExecutionException e
      (.getCause e))
    (catch CompletionException e
      (.getCause e))))

(defn- impl-then
  [^CompletionStage cf cb]
  (-> (.thenApplyAsync cf (function cb) *executor*)
      (Promise.)))

(defn- impl-error
  [^CompletionStage cf callback]
  (->> (function #(callback (.getCause %)))
       (.exceptionally cf)
       (Promise.)))

(defn- impl-deliver
  [^CompletionStage cf v]
  (if (instance? Throwable v)
    (.completeExceptionally cf v)
    (.complete cf v)))

(alter-meta! #'->Promise assoc :private true)

(extend-protocol proto/IPromiseFactory
  clojure.lang.Fn
  (promise [func]
    (let [futura (CompletableFuture.)
          promise (Promise. futura)]
      (schedule (fn []
                  (try
                    (func #(proto/deliver promise %))
                    (catch Throwable e
                      (proto/deliver promise e)))))
      promise))

  Throwable
  (promise [e]
    (let [p (proto/promise nil)]
      (proto/deliver p e)
      p))

  CompletionStage
  (promise [cs]
    (Promise. cs))

  Promise
  (promise [p]
    (proto/then p identity))

  manifold.deferred.IDeferred
  (promise [d]
    (let [pr (proto/promise nil)
          callback #(proto/deliver pr %)]
      (md/on-realized d callback callback)
      pr))

  nil
  (promise [_]
    (Promise. (CompletableFuture.)))

  Object
  (promise [v]
    (let [pr (proto/promise nil)]
      (proto/deliver pr v)
      pr)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn promise
  "A promise constructor.

  This is a polymorphic function and this is a list of
  possible arguments:

  - throwable
  - plain value
  - factory function

  In case of the initial value is instance of `Throwable`, rejected
  promise will be retrned. In case of a plain value (not throwable),
  a resolved promise will be returned. And finally, if a function
  or any callable is provided, that function will be executed with
  one argument as callback for mark the promise resolved or rejected.

      (promise (fn [deliver]
                 (future
                   (Thread/sleep 200)
                   (deliver 1))))

  The body of that function can be asynchronous and the promise can
  be freely resolved in other thread."
  ([] (proto/promise nil))
  ([v] (proto/promise v)))

(defn promise?
  "Returns true if `p` is a promise
  instance."
  [p]
  (satisfies? proto/IPromise p))

(defn done?
  "Returns true if promise `p` is
  done independently if successfully
  o exceptionally."
  [p]
  (proto/done? p))

(defn rejected?
  "Returns true if promise `p` is
  completed exceptionally."
  [p]
  (proto/rejected? p))

(defn resolved?
  "Returns true if promise `p` is
  completed successfully."
  [p]
  (proto/resolved? p))

(defn pending?
  "Returns true if promise `p` is
  stil in pending state."
  [p]
  (not (proto/done? p)))

(defn promise->future
  "Converts a promise in a CompletableFuture instance."
  [^Promise p]
  (.-cf p))

(defn deliver
  "Mark the promise as completed or rejected with optional
  value.

  If value is not specified `nil` will be used. If the value
  is instance of `Throwable` the promise will be rejected."
  ([p]
   (proto/deliver p nil))
  ([p v]
   (proto/deliver p v)))

(defn all
  "Given an array of promises, return a promise
  that is resolved  when all the items in the
  array are resolved."
  [promises]
  (let [xform (comp
               (map promise)
               (map promise->future))]
    (-> (into-array CompletableFuture (sequence xform promises))
        (CompletableFuture/allOf)
        (Promise.)
        (proto/then (fn [_] (mapv deref promises))))))

(defn any
  "Given an array of promises, return a promise
  that is resolved when first one item in the
  array is resolved."
  [promises]
  (let [xform (comp
               (map promise)
               (map promise->future))]
    (->> (sequence xform promises)
         (into-array CompletableFuture)
         (CompletableFuture/anyOf)
         (Promise.))))

(defn then
  "A chain helper for promises."
  [p callback]
  (proto/then p callback))

(defn catch
  "Catch all promise chain helper."
  [p callback]
  (proto/error p callback))

(defn reason
  "Get the rejection reason of this promise.
  Throws an error if the promise isn't rejected."
  [p]
  (let [e (m/extract p)]
    (when (instance? Throwable e)
      e)))
