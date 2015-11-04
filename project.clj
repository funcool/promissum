(defproject funcool/promissum "0.3.3"
  :description "A composable promise/future library for Clojure."
  :url "https://github.com/funcool/promissum"
  :license {:name "BSD (2 Clause)"
            :url "http://opensource.org/licenses/BSD-2-Clause"}
  :dependencies [[org.clojure/clojure "1.7.0" :scope "provided"]
                 [funcool/cats "1.0.0"]]
  :deploy-repositories {"releases" :clojars
                        "snapshots" :clojars}
  :source-paths ["src"]
  :test-paths ["test"]
  :jar-exclusions [#"\.swp|\.swo|user.clj"]
  :javac-options ["-target" "1.8" "-source" "1.8" "-Xlint:-options"]
  :profiles {:dev {:codeina {:sources ["src"]
                             :reader :clojure
                             :target "doc/dist/latest/api"
                             :src-uri "http://github.com/funcool/cats/blob/master/"
                             :src-uri-prefix "#L"}
                   :plugins [[funcool/codeina "0.3.0"]
                             [lein-ancient "0.6.7"
                              :exclusions [org.clojure/tools.reader]]]}})
