(ns shacl.cli-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shacl.cli :as cli]))

(def ^:private dir "test/resources/fixtures/")

(def ^:private context
  ["--context" (str "https://example.org/person/context=" dir "person-context.jsonld")])

(defn- run
  "The exit status, stdout and stderr of a CLI run."
  [& args]
  (let [err (java.io.StringWriter.)
        status (volatile! nil)
        out (with-out-str
              (binding [*err* err]
                (vreset! status (cli/run (flatten args)))))]
    {:status @status :out out :err (str err)}))

(defn- shapes [name] ["--shapes" (str dir name)])

(defn- data [& names] (map #(str dir %) names))

(deftest exit-status
  (testing "0 when the data conforms"
    (is (zero? (:status (run (shapes "person-shapes.ttl") context (data "alice.jsonld" "dan.ttl")))))
    (is (zero? (:status (run "--help")))))
  (testing "1 when it does not"
    (is (= 1 (:status (run (shapes "person-shapes.ttl") context (data "bob.jsonld")))))
    (is (= 1 (:status (run (shapes "price-shapes.ttl") (data "price-exponent.jsonld"))))))
  (testing "2 on a usage error"
    (doseq [args [[]
                  (data "alice.jsonld")
                  (shapes "person-shapes.ttl")
                  [(shapes "person-shapes.ttl") "--format" "xml" (data "alice.jsonld")]
                  [(shapes "person-shapes.ttl") "--context" "no-equals" (data "alice.jsonld")]
                  [(shapes "person-shapes.ttl") "--bogus" (data "alice.jsonld")]
                  ["--shapes"]]]
      (let [{:keys [status err]} (run args)]
        (is (= 2 status) (pr-str args))
        (is (str/includes? err "Usage:") (pr-str args)))))
  (testing "2 on an input error"
    (is (= 2 (:status (run (shapes "person-shapes.ttl") (data "alice.jsonld")))))
    (is (= 2 (:status (run (shapes "missing.ttl") context (data "alice.jsonld")))))
    (is (= 2 (:status (run (shapes "person-shapes.ttl") (data "missing.jsonld")))))))

(deftest ontology-option-mixes-in-the-ontology
  (is (= 1 (:status (run (shapes "person-shapes.ttl") context (data "erin.jsonld")))))
  (is (zero? (:status (run (shapes "person-shapes.ttl")
                           context
                           "--ontology" (str dir "ontology.ttl")
                           (data "erin.jsonld"))))))

(deftest formats
  (let [args [(shapes "person-shapes.ttl") context (data "bob.jsonld")]
        components #{"http://www.w3.org/ns/shacl#OrConstraintComponent"
                     "http://www.w3.org/ns/shacl#NodeConstraintComponent"
                     "http://www.w3.org/ns/shacl#MaxInclusiveConstraintComponent"}]
    (testing "text is the default: one tab-separated line per result, then a summary"
      (let [lines (str/split-lines (:out (run args)))]
        (is (= "does not conform: 3 result(s)" (last lines)))
        (is (= components (set (map #(nth (str/split % #"\t") 3) (butlast lines)))))
        (is (some #(str/starts-with? % "violation\thttp://example.org/bob\t-\t") lines))))
    (testing "edn"
      (let [report (edn/read-string (:out (run args "--format" "edn")))]
        (is (false? (:conforms? report)))
        (is (= components (set (map :component (:results report)))))
        (is (not-any? :focus-node (:results report)))))
    (testing "json"
      (let [report (json/read-str (:out (run args "--format" "json")))]
        (is (false? (get report "conforms")))
        (is (= components (set (map #(get % "component") (get report "results")))))
        (is (= #{"violation"} (set (map #(get % "severity") (get report "results")))))
        (is (= #{"focus" "path" "component" "severity" "value" "message" "sourceShape"}
               (set (mapcat keys (get report "results"))))))))
  (testing "a conforming run"
    (is (= "conforms\n" (:out (run (shapes "person-shapes.ttl") context (data "alice.jsonld")))))))
