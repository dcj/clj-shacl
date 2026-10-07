(ns shacl.core-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shacl.core :as shacl])
  (:import (org.apache.jena.graph Graph GraphMemFactory GraphUtil Node NodeFactory Triple)
           (org.apache.jena.riot Lang RDFParser)
           (org.apache.jena.shacl Shapes)))

(def ^:private context-url "https://example.org/person/context")

(defn- fixture [name] (io/resource (str "fixtures/" name)))

(def ^:private contexts {context-url (fixture "person-context.jsonld")})

(defn- read-people [& names]
  (shacl/read-jsonld (map fixture names) {:contexts contexts}))

(def ^:private person-shapes (delay (shacl/read-turtle (fixture "person-shapes.ttl"))))

(defn- ntriples ^Graph [source]
  (let [graph (GraphMemFactory/createDefaultGraph)]
    (with-open [in (io/input-stream source)]
      (-> (RDFParser/create)
          (.source in)
          (.lang Lang/NTRIPLES)
          (.parse graph)))
    graph))

(defn- keyset [{:keys [results]}]
  (set (map (juxt :focus :path :component) results)))

(def ^:private sh "http://www.w3.org/ns/shacl#")
(def ^:private ex "http://example.org/")

(deftest read-jsonld-serves-contexts-from-the-map
  (let [graph (read-people "alice.jsonld")]
    (is (= 7 (.size graph)))
    (is (true? (:conforms? (shacl/validate graph @person-shapes))))))

(deftest read-jsonld-with-an-inline-context
  (let [graph (shacl/read-jsonld [(fixture "carol.jsonld")])]
    (is (= 3 (.size graph)))
    (is (true? (:conforms? (shacl/validate graph @person-shapes))))))

(deftest read-jsonld-accepts-a-file-path-string
  (is (= 3 (.size (shacl/read-jsonld ["test/resources/fixtures/carol.jsonld"])))))

(deftest read-jsonld-gives-each-source-its-own-blank-nodes
  (let [graph (read-people "alice.jsonld" "bob.jsonld")
        blanks (into #{}
                     (comp (map #(.getSubject ^Triple %))
                           (filter #(.isBlank ^Node %)))
                     (iterator-seq (.find graph)))]
    (is (= 2 (count blanks)))))

(deftest read-jsonld-refuses-a-context-not-in-the-map
  (testing "no :contexts at all"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (shacl/read-jsonld [(fixture "alice.jsonld")])))]
      (is (= {:url context-url :contexts []} (ex-data e)))))
  (testing "a map that lacks the URL"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (shacl/read-jsonld [(fixture "alice.jsonld")]
                                            {:contexts {"https://example.org/other" (fixture "person-context.jsonld")}})))]
      (is (= context-url (:url (ex-data e))))
      (is (= ["https://example.org/other"] (:contexts (ex-data e)))))))

(deftest an-exponent-decimal-fails-sh-datatype
  (let [report (shacl/validate (shacl/read-jsonld [(fixture "price-exponent.jsonld")])
                               (shacl/read-turtle (fixture "price-shapes.ttl")))
        [r & more] (:results report)]
    (is (false? (:conforms? report)))
    (is (empty? more))
    (is (= {:focus "http://example.org/p1"
            :path "http://example.org/unitPrice"
            :component (str sh "DatatypeConstraintComponent")
            :severity :violation
            :value "-9e-05"}
           (select-keys r [:focus :path :component :severity :value])))))

(deftest validate-reports-results-as-data
  (let [report (shacl/validate (read-people "bob.jsonld") @person-shapes)
        bob (str ex "bob")]
    (is (false? (:conforms? report)))
    (is (= #{[bob nil (str sh "OrConstraintComponent")]
             [bob (str ex "address") (str sh "NodeConstraintComponent")]
             [bob (str ex "age") (str sh "MaxInclusiveConstraintComponent")]}
           (keyset report)))
    (testing "the focus node is the Jena node"
      (is (every? #(= bob (.getURI ^Node (:focus-node %))) (:results report))))
    (testing "a node-level sh:or result has a nil :path"
      (is (= [nil]
             (->> (:results report)
                  (filter #(= (str sh "OrConstraintComponent") (:component %)))
                  (mapv :path)))))
    (testing "values, severities and source shapes"
      (let [age (first (filter #(= (str ex "age") (:path %)) (:results report)))]
        (is (= "151" (:value age)))
        (is (= :violation (:severity age)))
        (is (string? (:message age)))
        (is (re-matches #"_:.+" (:source-shape age)))))))

(deftest validate-accepts-parsed-shapes
  (let [shapes (Shapes/parse ^Graph @person-shapes)]
    (is (= (keyset (shacl/validate (read-people "bob.jsonld") @person-shapes))
           (keyset (shacl/validate (read-people "bob.jsonld") shapes))))))

(deftest inoculate-matches-pyshacl
  (doseq [[ontology inoculated] [["ontology.ttl" "ontology-inoculated.nt"]
                                 ["ontology-edge-cases.ttl" "ontology-edge-cases-inoculated.nt"]]]
    (testing ontology
      (let [expected (ntriples (fixture inoculated))
            actual (shacl/inoculate (shacl/read-turtle (fixture ontology)))]
        (is (= (.size expected) (.size actual)))
        (is (.isIsomorphicWith expected actual))))))

(defn- ex-iri ^Node [local] (NodeFactory/createURI (str ex local)))

(defn- matches [^Graph g s p o]
  (count (iterator-seq (.find g (or s Node/ANY) (or p Node/ANY) (or o Node/ANY)))))

(deftest inoculate-deep-clones-named-individuals
  (let [actual (shacl/inoculate (shacl/read-turtle (fixture "ontology.ttl")))
        hq (ex-iri "headquarters")]
    (testing "the individual's own triples, through its blank nodes"
      (is (= 1 (matches actual hq (ex-iri "postalCode") nil)))
      (is (= 1 (matches actual nil (ex-iri "lat") nil))))
    (testing "triples whose object it is"
      (is (= 1 (matches actual (ex-iri "acme") (ex-iri "address") hq))))
    (testing "an individual that is not an owl:NamedIndividual"
      (is (zero? (matches actual (ex-iri "bob") nil nil))))))

(deftest inoculated-ontology-satisfies-class-constraints
  (let [data (read-people "erin.jsonld")
        erin (str ex "erin")]
    (is (= #{[erin (str ex "address") (str sh "ClassConstraintComponent")]
             [erin (str ex "address") (str sh "NodeConstraintComponent")]}
           (keyset (shacl/validate data @person-shapes))))
    (GraphUtil/addInto data (shacl/inoculate (shacl/read-turtle (fixture "ontology.ttl"))))
    (is (true? (:conforms? (shacl/validate data @person-shapes))))))

(deftest targets-any?-detects-a-namespace-mismatch
  (is (true? (shacl/targets-any? (read-people "alice.jsonld") @person-shapes)))
  (let [other (shacl/read-jsonld [(fixture "other-namespace.jsonld")])]
    (is (false? (shacl/targets-any? other @person-shapes)))
    (is (true? (:conforms? (shacl/validate other @person-shapes))))))
