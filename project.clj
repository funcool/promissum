(defproject funcool/promissum "0.1.0"
  :description "A composable promise/future library for Clojure."
  :url "https://github.com/funcool/promissum"
  :license {:name "BSD (2 Clause)"
            :url "http://opensource.org/licenses/BSD-2-Clause"}
  :dependencies [[org.clojure/clojure "1.7.0" :scope "provided"]
                 [funcool/cats "0.5.0"]
                 [manifold "0.1.0"]]
  :deploy-repositories {"releases" :clojars
                        "snapshots" :clojars}
  :source-paths ["src"]
  :test-paths ["test"]
  :jar-exclusions [#"\.swp|\.swo|user.clj"]
  :javac-options ["-target" "1.8" "-source" "1.8" "-Xlint:-options"]
  :profiles {:dev {:codeina {:sources ["src"]
                             :language :clojure
                             :output-dir "doc/dist/latest/api"}
                   :plugins [[funcool/codeina "0.1.0-SNAPSHOT"
                              :exclusions [org.clojure/clojure]]
                             [lein-ancient "0.6.7" :exclusions [org.clojure/tools.reader]]]}})
