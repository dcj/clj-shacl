(ns shacl.core
  "Read RDF, mix an ontology into data, and validate it against SHACL shapes
  with Apache Jena.

  Every graph is an `org.apache.jena.graph.Graph`. For the Model API, wrap one
  with `org.apache.jena.rdf.model.ModelFactory/createModelForGraph`.

  A source is a String naming a file, or anything else
  `clojure.java.io/input-stream` accepts."
  (:require [clojure.java.io :as io])
  (:import (com.apicatalog.jsonld JsonLdError JsonLdErrorCode JsonLdOptions)
           (com.apicatalog.jsonld.document JsonDocument)
           (com.apicatalog.jsonld.loader DocumentLoader)
           (org.apache.jena.graph Graph GraphMemFactory Node NodeFactory Triple)
           (org.apache.jena.riot Lang RDFParser)
           (org.apache.jena.riot.lang LangJSONLD11)
           (org.apache.jena.shacl ShaclValidator Shapes ValidationReport)
           (org.apache.jena.shacl.validation ReportEntry Severity)
           (org.apache.jena.sparql.path P_Link)
           (org.apache.jena.sparql.util Context)))

(set! *warn-on-reflection* true)

(defn- input-stream
  ^java.io.InputStream [source]
  (io/input-stream (if (string? source) (io/file source) source)))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(defn- context-loader
  "A Titanium DocumentLoader serving each URL in `contexts` from its source.
  Any other URL is recorded in `refused` and fails the parse."
  [contexts refused]
  (reify DocumentLoader
    (loadDocument [_ url _options]
      (let [u (str url)]
        (if-let [source (get contexts u)]
          (with-open [in (input-stream source)]
            (JsonDocument/of in))
          (do
            (vreset! refused u)
            (throw (JsonLdError. JsonLdErrorCode/LOADING_DOCUMENT_FAILED
                                 (str "JSON-LD document not in :contexts: " u)))))))))

(defn- refused-url-error
  [url contexts cause]
  (ex-info (str "JSON-LD document " url " is not in :contexts")
           {:url url
            :contexts (vec (sort (keys contexts)))}
           cause))

(defn read-jsonld
  "Parse JSON-LD `sources` into one new Graph.

  Each source gets its own blank nodes, so `_:b0` in two sources names two
  nodes.

  `:contexts` maps a URL string to the source of the document served for it.
  A remote context at any other URL throws ex-info with `:url` and
  `:contexts`; nothing is fetched."
  (^Graph [sources]
   (read-jsonld sources {}))
  (^Graph [sources {:keys [contexts]}]
   (let [contexts (into {} (map (fn [[k v]] [(str k) v])) contexts)
         refused (volatile! nil)
         options (doto (JsonLdOptions.)
                   (.setDocumentLoader (context-loader contexts refused)))
         context (doto (Context.)
                   (.set LangJSONLD11/JSONLD_OPTIONS options))
         graph (GraphMemFactory/createDefaultGraph)]
     (doseq [source sources]
       (try
         (with-open [in (input-stream source)]
           (-> (RDFParser/create)
               (.source in)
               (.lang Lang/JSONLD)
               (.context context)
               (.parse graph)))
         (catch Exception e
           (if-let [url @refused]
             (throw (refused-url-error url contexts e))
             (throw e)))))
     graph)))

(defn read-turtle
  "Parse one Turtle source into a new Graph."
  ^Graph [source]
  (let [graph (GraphMemFactory/createDefaultGraph)]
    (with-open [in (input-stream source)]
      (-> (RDFParser/create)
          (.source in)
          (.lang Lang/TURTLE)
          (.parse graph)))
    graph))

;; ---------------------------------------------------------------------------
;; inoculate: a port of pyshacl 0.40.1 rdfutil/inoculate.py and clone.py
;; ---------------------------------------------------------------------------

(def ^:private rdf "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
(def ^:private rdfs "http://www.w3.org/2000/01/rdf-schema#")
(def ^:private owl "http://www.w3.org/2002/07/owl#")
(def ^:private sh "http://www.w3.org/ns/shacl#")

(defn- iri ^Node [s] (NodeFactory/createURI s))

(def ^:private rdf-type (iri (str rdf "type")))
(def ^:private rdf-first (iri (str rdf "first")))
(def ^:private rdf-rest (iri (str rdf "rest")))
(def ^:private rdf-nil (iri (str rdf "nil")))
(def ^:private owl-named-individual (iri (str owl "NamedIndividual")))
(def ^:private owl-same-as (iri (str owl "sameAs")))

(def ^:private rdfs-classes
  (mapv #(iri (str rdfs %))
        ["Class" "Container" "ContainerMembershipProperty" "Datatype" "Literal"
         "Resource"]))

(def ^:private owl-classes
  (mapv #(iri (str owl %))
        ["AllDifferent" "AllDisjointClasses" "AllDisjointProperties" "Annotation"
         "AnnotationProperty" "AsymmetricProperty" "Axiom" "Class" "DataRange"
         "DatatypeProperty" "DeprecatedClass" "DeprecatedProperty"
         "FunctionalProperty" "InverseFunctionalProperty" "IrreflexiveProperty"
         "NamedIndividual" "NegativePropertyAssertion" "ObjectProperty" "Ontology"
         "OntologyProperty" "ReflexiveProperty" "Restriction" "SymmetricProperty"
         "TransitiveProperty"]))

(def ^:private rdfs-properties
  (mapv #(iri (str rdfs %))
        ["comment" "domain" "isDefinedBy" "label" "member" "range" "seeAlso"
         "subClassOf" "subPropertyOf"]))

(def ^:private owl-properties
  (mapv #(iri (str owl %))
        ["allValuesFrom" "annotatedProperty" "annotatedSource" "annotatedTarget"
         "assertionProperty" "cardinality" "complementOf" "datatypeComplementOf"
         "differentFrom" "disjointUnionOf" "disjointWith" "distinctMembers"
         "equivalentClass" "equivalentProperty" "hasKey" "hasSelf" "hasValue"
         "intersectionOf" "inverseOf" "maxCardinality" "maxQualifiedCardinality"
         "members" "minCardinality" "minQualifiedCardinality" "onClass"
         "onDataRange" "onDatatype" "onProperties" "onProperty" "oneOf"
         "propertyChainAxiom" "propertyDisjointWith" "qualifiedCardinality"
         "sameAs" "someValuesFrom" "sourceIndividual" "targetIndividual"
         "targetValue" "unionOf" "withRestrictions" "backwardCompatibleWith"
         "deprecated" "incompatibleWith" "priorVersion" "versionInfo"
         "bottomDataProperty" "topDataProperty" "bottomObjectProperty"
         "topObjectProperty" "imports" "versionIRI"]))

(def ^:private max-recursion 10)

(defn- find-triples
  [^Graph g s p o]
  (vec (iterator-seq (.find g
                            (or s Node/ANY)
                            (or p Node/ANY)
                            (or o Node/ANY)))))

(defn- objects [g s p] (mapv #(.getObject ^Triple %) (find-triples g s p nil)))

(defn- predicates [g s] (distinct (map #(.getPredicate ^Triple %) (find-triples g s nil nil))))

(defn- value [g s p] (first (objects g s p)))

(defn- add! [^Graph g s p o] (.add g (Triple/create s p o)))

(defn- fresh-blank ^Node [] (NodeFactory/createBlankNode))

(defn- list-items
  "rdflib Graph.items: the rdf:first of each node along rdf:rest."
  [g head]
  (loop [node head
         chain #{head}
         items []]
    (if (nil? node)
      items
      (let [item (value g node rdf-first)
            next-node (value g node rdf-rest)]
        (when (and next-node (contains? chain next-node))
          (throw (ex-info "List contains a recursive rdf:rest reference" {:node head})))
        (recur next-node
               (conj chain next-node)
               (cond-> items item (conj item)))))))

(defn- list-end
  [g head]
  (loop [node head]
    (let [rest-node (value g node rdf-rest)]
      (if (or (nil? rest-node) (= rest-node rdf-nil))
        node
        (recur rest-node)))))

(defn- list-append!
  "rdflib Collection.append."
  [^Graph target head item]
  (let [end (list-end target head)
        end (if (.contains target ^Node end rdf-first Node/ANY)
              (let [node (fresh-blank)]
                (.remove target end rdf-rest Node/ANY)
                (add! target end rdf-rest node)
                node)
              end)]
    (add! target end rdf-first item)
    (add! target end rdf-rest rdf-nil)))

(declare clone-node)

(defn- clone-list
  [g ^Node head target recursion deep?]
  (let [cloned (if (.isBlank head) (fresh-blank) head)]
    (doseq [item (list-items g head)]
      (list-append! target
                    cloned
                    (clone-node g item target (inc recursion) deep?)))
    cloned))

(defn- clone-properties!
  "Add a clone of each triple about `node` to `target`, with subject `subject`."
  [g node target subject recursion deep?]
  (doseq [p (predicates g node)
          o (objects g node p)]
    (add! target
          subject
          p
          (clone-node g
                      o
                      target
                      (inc recursion)
                      (and deep?
                           (not (and (= p owl-same-as) (= o node))))))))

(defn- clone-blank-node
  [g node target recursion]
  (let [cloned (fresh-blank)
        preds (when (< recursion max-recursion) (predicates g node))]
    (cond
      (empty? preds) cloned
      (some #{rdf-first} preds) (clone-list g node target recursion false)
      :else (do (clone-properties! g node target cloned recursion false)
                cloned))))

(defn- clone-node
  [g ^Node node target recursion deep?]
  (cond
    (.isLiteral node) node
    (.isBlank node) (clone-blank-node g node target (inc recursion))
    (not (.isURI node)) (throw (ex-info "Cannot clone node" {:node node}))
    (not deep?) node
    :else (let [preds (when (< recursion max-recursion) (predicates g node))]
            (cond
              (empty? preds) node
              (some #{rdf-first} preds) (clone-list g node target recursion false)
              :else (do (clone-properties! g node target node recursion true)
                        node)))))

(defn inoculate
  "The triples pyshacl 0.40.1's `inoculate()` copies from `ontology` into an
  empty data graph, as a new Graph: RDFS and OWL class and property axioms,
  with blank-node structures cloned.

  Each owl:NamedIndividual is deep-cloned with every triple whose object it
  is, as pyshacl does.

  Union the result into the data graph to validate, for example with
  `org.apache.jena.graph.GraphUtil/addInto`."
  ^Graph [^Graph ontology]
  (let [target (GraphMemFactory/createDefaultGraph)
        cloned-blanks (volatile! {})
        named (volatile! {})
        clone-top-blank (fn [node]
                          (or (get @cloned-blanks node)
                              (let [cloned (clone-blank-node ontology node target 0)]
                                (vswap! cloned-blanks assoc node cloned)
                                cloned)))
        top (fn [^Node node]
              (if (.isBlank node) (clone-top-blank node) node))]
    (doseq [klass (concat rdfs-classes owl-classes)
            ^Triple t (find-triples ontology nil rdf-type klass)
            :let [s (.getSubject t)]]
      (add! target
            (cond
              (.isBlank s) (clone-top-blank s)
              (not= klass owl-named-individual) s
              :else (or (get @named s)
                        (let [cloned (clone-node ontology s target 0 true)]
                          (vswap! named assoc s cloned)
                          cloned)))
            rdf-type
            klass))
    (doseq [p (concat rdfs-properties owl-properties)
            ^Triple t (find-triples ontology nil p nil)]
      (add! target (top (.getSubject t)) p (top (.getObject t))))
    (doseq [individual (keys @named)
            ^Triple t (find-triples ontology nil nil individual)]
      (add! target (top (.getSubject t)) (.getPredicate t) individual))
    target))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn targets-any?
  "True when some class asserted with rdf:type in `data` is a shape target in
  `shapes`: the object of an sh:targetClass, or itself an sh:NodeShape.

  A false result means every class-targeted shape selects no focus node, so
  validation conforms without checking anything."
  [^Graph data ^Graph shapes]
  (let [data-types (set (objects data nil rdf-type))
        targeted (into (set (objects shapes nil (iri (str sh "targetClass"))))
                       (comp (map #(.getSubject ^Triple %))
                             (filter data-types))
                       (find-triples shapes nil rdf-type (iri (str sh "NodeShape"))))]
    (boolean (some targeted data-types))))

(defn- node-str
  "An IRI's string, `_:` and a blank node's label, or a literal's lexical form."
  [^Node node]
  (cond
    (nil? node) nil
    (.isURI node) (.getURI node)
    (.isBlank node) (str "_:" (.getBlankNodeLabel node))
    (.isLiteral node) (.getLiteralLexicalForm node)
    :else (str node)))

(def ^:private severities
  {Severity/Violation :violation
   Severity/Warning :warning
   Severity/Info :info})

(defn- result
  [^ReportEntry e]
  (let [path (.resultPath e)
        severity (.severity e)]
    {:focus-node (.focusNode e)
     :focus (node-str (.focusNode e))
     :path (cond
             (nil? path) nil
             (instance? P_Link path) (node-str (.getNode ^P_Link path))
             :else (str path))
     :component (node-str (.sourceConstraintComponent e))
     :severity (get severities severity (node-str (.level severity)))
     :value (node-str (.value e))
     :message (.message e)
     :source-shape (node-str (.source e))}))

(defn validate
  "Validate `data` against `shapes`, a Graph or a parsed
  `org.apache.jena.shacl.Shapes`.

  Returns `{:conforms? bool :results [...]}`. Each result holds
  `:focus-node`, the Jena node, and these strings: `:focus`, `:path` (nil
  for a node-level result; the SPARQL path syntax for a complex path),
  `:component`, `:value`, `:message` and `:source-shape`. `:severity` is
  `:violation`, `:warning` or `:info`, or the IRI of another severity.

  IRIs are strings, a blank node is `_:` and its label, and a literal is its
  lexical form."
  [^Graph data shapes]
  (let [shapes (if (instance? Shapes shapes) shapes (Shapes/parse ^Graph shapes))
        ^ValidationReport report (.validate (ShaclValidator/get) ^Shapes shapes data)]
    {:conforms? (.conforms report)
     :results (mapv result (.getEntries report))}))
