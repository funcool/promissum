(ns promissum.core-tests
  (:require [clojure.test :refer :all]
            [manifold.deferred :as md]
            [cats.core :as m]
            [promissum.core :as p]))

(deftest promise-constructors
  (let [p1 (p/promise 1)]
    (is (p/promise? p1))
    (is (p/resolved? p1))
    (is (not (p/rejected? p1)))
    (is (not (p/pending? p1))))
  (let [p1 (p/promise)]
    (is (p/promise? p1))
    (is (not (p/resolved? p1)))
    (is (not (p/rejected? p1)))
    (is (p/pending? p1)))
  (let [p1 (p/promise (ex-info "" {}))]
    (is (p/promise? p1))
    (is (not (p/resolved? p1)))
    (is (p/rejected? p1))
    (is (not (p/pending? p1))))
  (let [p1 (p/promise)
        p2 (p/promise p1)]
    (is (identical? p1 p2)))
  (let [p1 (p/promise)
        _  (p/deliver p1 2)]
    (is (p/promise? p1))
    (is (p/resolved? p1))
    (is (not (p/rejected? p1)))
    (is (not (p/pending? p1))))
  )

;; (defmacro thread
;;   [& body]
;;   (let [func# (fn [] ~@body)
;;         thr#  (Thread. ^Runnable func#)]
;;     (.setDaemon thr# true)
;;     (.start thr#)))

(deftest promise-extract
  (let [p1 (p/promise 1)]
    (is (= 1 @p1)))

  (let [p1 (p/promise (ex-info "foobar" {:foo 1}))]
    (is (= "foobar" (.getMessage (m/extract p1))))
    (try
      @p1
      (catch Exception e
        (is (= {:foo 1} (.. e getCause getData)))))))

(deftest promise-operations
  (testing "Simple delivering"
    (let [p1 (p/promise)
          _  (p/future
               (p/deliver p1 2))]
      (is (= 2 @p1))))

  (testing "Chaining using then"
    (let [p1 (p/future
               (Thread/sleep 200)
               2)
          p2 (p/then p1 inc)
          p3 (p/then p2 inc)]
      (is (= 4 @p3))))

  (testing "Deref rejected promise"
    (let [p1 (p/future
               (throw (ex-info "foobar" {})))]
      (is (thrown? java.util.concurrent.ExecutionException @p1))))

  (testing "Await rejected promise"
    (let [p1 (p/future
               (throw (ex-info "foobar" {})))]
      (is (thrown? clojure.lang.ExceptionInfo (p/await p1)))))

  (testing "Reject promise in the middle of chain"
    (let [p1 (p/future 1)
          p2 (p/then p1 (fn [v]
                          (throw (ex-info "foobar" {:msg "foo"}))))
          p3 (p/catch p2 (fn [e]
                           (:msg (.getData e))))]
      (is (= "foo" @p3))))

  (testing "Synchronize two promises."
    (let [p1 (p/all [(p/promise 1) (p/promise 2)])]
      (is (= @p1 [1 2]))))

  (testing "Arbitrary select first resolved promise"
    (let [p1 (p/any [(p/promise 1) (p/promise (ex-info "" {}))])]
      (is (= @p1 1))))
  )

(deftest futures-replacement
  (testing "Simple future execution."
    (is (= 3 @(future (+ 1 2))))))


(deftest manifold-integration
  (testing "Build promise from mainfold deferred and resolve it."
    (let [df (md/deferred)
          pr (p/promise df)]
      (md/success! df 1)
      (is (= @pr 1))))


  (testing "Build promise from manifold deferred and reject it."
    (let [df (md/deferred)
          pr (p/promise df)]
      (md/error! df (ex-info "foobar" {}))
      (let [e (m/extract pr)]
        (is (= "foobar" (.getMessage e))))))
  )
