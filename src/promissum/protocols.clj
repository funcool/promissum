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

(ns promissum.protocols
  (:refer-clojure :exclude [promise deliver map await]))

(defprotocol IState
  "Additional state related abstraction."
  (rejected? [_] "Returns true if a promise is rejected.")
  (resolved? [_] "Returns true if a promise is resolved.")
  (done? [_] "Retutns true if a promise is already done."))

(defprotocol IFuture
  "A basic future abstraction."
  (map [_ callback] "Chain a promise.")
  (flatmap [_ callback] "Chain a promise.")
  (error [_ callback] "Catch a error in a promise."))

(defprotocol IAwaitable
  (await
    [awaitable]
    [awaitable ms]
    [awaitable ms default]))

(defprotocol IPromise
  "A basic promise abstraction."
  (deliver [_ value] "Deliver a value into promise."))

(defprotocol IPromiseFactory
  "A promise constructor abstraction."
  (promise [_] "Create a promise instance."))
