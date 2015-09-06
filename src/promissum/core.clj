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
  (:refer-clojure :exclude [future promise deliver await])
  (:require [cats.core :as m]
            [cats.context :as mc]
            [cats.protocols :as cats]
            [promissum.protocols :as proto]
  (:import java.util.concurrent.CompletableFuture
           java.util.concurrent.CompletionStage
           java.util.concurrent.TimeoutException
           java.util.concurrent.ExecutionException
           java.util.concurrent.CompletionException
           java.util.concurrent.TimeUnit
           java.util.concurrent.Future
           java.util.concurrent.Executor
           java.util.concurrent.ForkJoinPool
           java.util.function.Function
           java.util.function.Supplier))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Concurrency
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^{:doc "The main executor service for schedule promises."
       :dynamic true}
  *executor* (ForkJoinPool/commonPool))

(defn function
  "Given an plain function `f`, return an
  instace of the java.util.concurrent.Function
  class."
  {:no-doc true}
  [f]
  (reify Function
    (apply [_ v] (f v))))

(defn schedule
  "Schedule a functon to execute in
  a provided executor service."
  {:no-doc true}
  ([func] (schedule *executor* func))
  ([^Executor executor ^Runnable func]
   (.execute executor func)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare promise-context)

(defn- impl-get-context
  [^CompletionStage cs]
  promise-context)

(defn- impl-extract
  [^CompletionStage cs]
  (try
    (.getNow cs nil)
    (catch ExecutionException e
      (.getCause e))
    (catch CompletionException e
      (.getCause e))))

(defn- impl-rejected?
  [^CompletionStage cs]
  (.isCompletedExceptionally cs))

(defn- impl-resolved?
  [^CompletionStage cs]
  (and (not (.isCompletedExceptionally cs))
       (not (.isCancelled cs))
       (.isDone cs)))

(defn- impl-done?
  [^CompletionStage cs]
  (.isDone cs))

(defn- impl-map
  [^CompletionStage cf cb]
  (.thenApplyAsync cf (function cb) *executor*))

(defn- impl-flatmap
  [^CompletionStage cf cb]
  (.thenComposeAsync cf (function cb) *executor*))

(defn- impl-error
  [^CompletionStage cs callback]
  (->> (function #(callback (.getCause %)))
       (.exceptionally cs)))

(defn- impl-deliver
  [^CompletableFuture cs v]
  (if (instance? Throwable v)
    (.completeExceptionally cs v)
    (.complete cs v)))

(defn- impl-deref
  [cs]
  (try
    (.get cs)
    (catch ExecutionException e
      (let [e' (.getCause e)]
        (.setStackTrace e' (.getStackTrace e))
        (throw e')))
    (catch CompletionException e
      (let [e' (.getCause e)]
        (.setStackTrace e' (.getStackTrace e))
        (throw e')))))

(defn- impl-await
  ([^Future cs]
   (try
     (.get cs)
     (catch ExecutionException e
       (let [e' (.getCause e)]
         (.setStackTrace e' (.getStackTrace e))
         (throw e')))
     (catch CompletionException e
       (let [e' (.getCause e)]
         (.setStackTrace e' (.getStackTrace e))
         (throw e')))))
  ([^Future cs ^long ms]
   (impl-await cs ms nil))
  ([^Future cs ^long ms default]
   (try
     (.get cs ms TimeUnit/SECONDS)
     (catch TimeoutException e
       default)
     (catch ExecutionException e
       (let [e' (.getCause e)]
         (.setStackTrace e' (.getStackTrace e))
         (throw e')))
     (catch CompletionException e
      (let [e' (.getCause e)]
        (.setStackTrace e' (.getStackTrace e))
        (throw e'))))))

(extend CompletionStage
  cats/Context
  {:get-context impl-get-context}

  cats/Extract
  {:extract impl-extract}

  proto/IState
  {:rejected? impl-rejected?
   :resolved? impl-resolved?
   :done? impl-done?}

  proto/IFuture
  {:map impl-map
   :flatmap impl-flatmap
   :error impl-error})

(extend Future
  proto/IAwaitable
  {:await impl-await})

(extend CompletableFuture
  cats/Context
  {:get-context impl-get-context}

  cats/Extract
  {:extract impl-extract}

  proto/IState
  {:rejected? impl-rejected?
   :resolved? impl-resolved?
   :done? impl-done?}

  proto/IFuture
  {:map impl-map
   :flatmap impl-flatmap
   :error impl-error}

  proto/IPromise
  {:deliver impl-deliver})

(extend-protocol proto/IPromiseFactory
  clojure.lang.Fn
  (promise [func]
    (let [promise (CompletableFuture.)]
      (schedule (fn []
                  (try
                    (func #(proto/deliver promise %))
                    (catch Throwable e
                      (proto/deliver promise e)))))
      promise))

  Throwable
  (promise [e]
    (let [p (CompletableFuture.)]
      (proto/deliver p e)
      p))

  CompletionStage
  (promise [cs]
    cs)

  Object
  (promise [v]
    (let [p (CompletableFuture.)]
      (proto/deliver p v)
      p)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Constructors

(defn promise
  "A promise constructor.

  This is a polymorphic function and this is a list of
  possible arguments:

  - throwable
  - plain value

  In case of the initial value is instance of `Throwable`, rejected
  promise will be retrned. In case of a plain value (not throwable),
  a resolved promise will be returned."
  ([] (CompletableFuture.))
  ([v] (proto/promise v)))

(defn resolved
  "Takes a value `v` and return a resolved promise
  with that value."
  [v]
  (let [cf (CompletableFuture.)]
    (.complete cf v)
    cf))

(defn rejected
  "Takes a error `e` and return a rejected promise
  with that error."
  [e]
  (let [cf (CompletableFuture.)]
    (.completeExceptionally cf e)
    cf))

(defmacro future
  "Takes a body of expressions and yields a promise object that will
  invoke the body in another thread.
  This is a drop in replacement for the clojure's builtin `future`
  function that return composable promises."
  [& body]
  `(let [suplier# (reify Supplier
                    (get [_]
                      ~@body))]
     (CompletableFuture/supplyAsync suplier# *executor*)))

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
  (-> (into-array CompletableFuture (sequence (map proto/promise) promises))
      (CompletableFuture/allOf)
      (proto/map (fn [_] (mapv deref promises)))))

(defn any
  "Given an array of promises, return a promise
  that is resolved when first one item in the
  array is resolved."
  [promises]
  (->> (sequence (map proto/promise) promises)
       (into-array CompletableFuture)
       (CompletableFuture/anyOf)))

(defn then
  "A chain helper for promises."
  [p callback]
  (proto/map p callback))

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

(defn await
  ([^CompletionStage cs]
   (proto/await cs))
  ([^CompletionStage cs ^long ms]
   (proto/await cs ms))
  ([^CompletionStage cs ^long ms ^Object default]
   (proto/await cs ms default)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Monad type implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^{:no-doc true}
  promise-context
  (reify
    cats/ContextClass
    (-get-level [_] mc/+level-default+)

    cats/Functor
    (-fmap [mn f mv]
      (impl-map mv f))

    cats/Applicative
    (-fapply [_ af av]
      (impl-map (all [af av])
                (fn [[afv avv]]
                  (afv avv))))

    cats/Monad
    (-mreturn [_ v]
      (p/-promise v))

    (-mbind [mn mv f]
      (impl-bind mv f))))
