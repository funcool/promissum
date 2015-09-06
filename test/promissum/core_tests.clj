(ns promissum.core-tests
  (:require [clojure.test :refer :all]
            [cats.core :as m]
            [cats.protocols :as mp]
            [cats.builtin :as mb]
            [promissum.core :as p]))

(deftest constructors-tests
  (testing "Promise can be created from value."
    (let [p1 (p/promise 1)]
      (is (p/promise? p1))
      (is (p/resolved? p1))
      (is (not (p/rejected? p1)))
      (is (not (p/pending? p1)))))

  (testing "Promise can be created without value."
    (let [p1 (p/promise)]
      (is (p/promise? p1))
      (is (not (p/resolved? p1)))
      (is (not (p/rejected? p1)))
      (is (p/pending? p1))))

  (testing "Promise can be created from exception."
    (let [p1 (p/promise (ex-info "" {}))]
      (is (p/promise? p1))
      (is (not (p/resolved? p1)))
      (is (p/rejected? p1))
      (is (not (p/pending? p1)))))

  (testing "Creating promise from promise."
    (let [p1 (p/promise)
          p2 (p/promise p1)]
      (is (identical? p1 p2))))

  (testing "Delivery with factory"
    (let [p (p/promise (fn [deliver]
                         (deliver 2)))]
      (is (= @p 2))))

  (testing "A `resolved` constructor."
    (is (= 2 @(p/resolved 2))))

  (testing "A `rejected` constructor with await"
    (let [exc (ex-info "foo" {:bar :baz})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (p/await (p/rejected exc))))))

  (testing "Future replacement"
    (is (= 3 @(future (+ 1 2)))))
  )

(deftest operations-tests
  (testing "Promise Extract"
    (let [p1 (p/promise 1)]
      (is (= 1 @p1)))

    (let [p1 (p/promise (ex-info "foobar" {:foo 1}))]
      (is (= "foobar" (.getMessage (m/extract p1))))
      (try
        @p1
        (catch Exception e
          (is (= {:foo 1} (.. e getCause getData)))))))

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

  (testing "Chaining using then"
    (let [p1 (p/future
               (Thread/sleep 200)
               2)
          p2 (p/chain p1 inc inc inc)]
      (is (= 5 @p2))))

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

(deftest cats-tests
  (testing "Promise is a functor."
    (let [p (m/pure p/promise-context 1)]
      (is (= 2 @(m/fmap inc p)))))

  (testing "Promise is a monad"
    (let [p (m/pure p/promise-context 1)]
      (is (= 2 @(m/>>= p (fn [x] (m/return (inc x)))))))

    (let [p1 (p/future 1)
          p2 (p/future 1)
          p3 (p/future 1)
          r   (m/mlet [x p1
                       y p2
                       z p3]
                (m/return (+ x y z)))]
      (is (= 3 @r))))

  (testing "First monad law: left identity"
    (let [p1 (m/pure p/promise-context 4)
          p2 (m/pure p/promise-context 4)
          vl  (m/>>= p2 #(m/pure p/promise-context %))]
      (is (= (p/await p1)
             (p/await vl)))))

  (testing "Second monad law: right identity"
    (let [p1 (p/future 3)
          rs  (m/>>= (p/future 3) m/return)]
      (is (= @p1 @rs))))

  (testing "Third monad law: associativity"
    (let [rs1 (m/>>= (m/mlet [x (p/future 2)
                              y (p/future (inc x))]
                       (m/return y))
                     (fn [y] (p/future (inc y))))
          rs2 (m/>>= (p/future 2)
                     (fn [x] (m/>>= (p/future (inc x))
                                    (fn [y] (p/future (inc y))))))]
      (is (= @rs1 @rs2))))

  (testing "Primise as semigroup"
    (let [c1 (p/promise {:a 1})
          c2 (p/promise {:b 2})
          r (m/mappend c1 c2)]
      (is (= {:a 1 :b 2} @r)))
    (let [c1 (p/promise {:a 1})
          c2 (p/promise (ex-info "" {:b 2}))
          r (m/mappend c1 c2)]
    (is (thrown? clojure.lang.ExceptionInfo (p/await r)))))

  (testing "Use promises in applicative do syntax"
    (letfn [(async-call [wait]
              (p/future
                (Thread/sleep 100)
                wait))]
      (let [result (m/alet [x (async-call 100)
                            y (async-call 100)]
                           (+ x y))]
        (is (p/promise? result))
        (is (= @result 200)))))
  )
