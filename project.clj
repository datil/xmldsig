(defproject org.clojars.sergeyminaev/xmldsig "0.1.1"

  :description "Clojure library to make working with Java API for XMLDsig easier"

  :url "http://example.com/FIXME"

  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :source-paths ["src/main/clojure"]
  :java-source-paths ["src/main/java"]

  :profiles {:test {:resource-paths ["test-resources"]}}

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.logging "0.4.1"]])
