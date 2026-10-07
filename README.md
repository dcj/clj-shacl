<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# clj-shacl

[![Clojars Project](https://img.shields.io/clojars/v/com.dcj/clj-shacl.svg)](https://clojars.org/com.dcj/clj-shacl)
[![md-docs](https://img.shields.io/badge/md--docs-included-green)](https://github.com/dcj/codox-md)
[![build-provenance](https://img.shields.io/badge/build--provenance-included-blue)](https://github.com/dcj/build-provenance)

Validate RDF against [SHACL](https://www.w3.org/TR/shacl/) shapes with [Apache Jena](https://jena.apache.org/documentation/shacl/), from Clojure or the command line. JSON-LD remote contexts are served from a map you supply, so parsing never fetches anything.

Requires Java 21 or later (Jena 6).

## Install

`deps.edn`:

```clojure
{:deps {com.dcj/clj-shacl {:mvn/version "0.2.0"}}}
```

## Usage

```clojure
(require '[shacl.core :as shacl])

(def shapes
  (shacl/read-turtle
   (.getBytes "@prefix sh: <http://www.w3.org/ns/shacl#> .
               @prefix ex: <http://example.org/> .
               ex:PersonShape a sh:NodeShape ;
                 sh:targetClass ex:Person ;
                 sh:property [ sh:path ex:name ; sh:minCount 1 ] .")))

(def data
  (shacl/read-jsonld
   [(.getBytes "{\"@context\": \"https://example.org/context\",
                 \"@id\": \"http://example.org/alice\",
                 \"@type\": \"Person\"}")]
   {:contexts {"https://example.org/context"
               (.getBytes "{\"@context\": {\"@vocab\": \"http://example.org/\"}}")}}))

(shacl/targets-any? data shapes)
;; => true

(shacl/validate data shapes)
;; => {:conforms? false
;;     :results [{:focus-node #object[org.apache.jena.graph.Node_URI ...]
;;                :focus "http://example.org/alice"
;;                :path "http://example.org/name"
;;                :component "http://www.w3.org/ns/shacl#MinCountConstraintComponent"
;;                :severity :violation
;;                :value nil
;;                :value-node nil
;;                :message "minCount[1]: Invalid cardinality: expected min 1: Got count = 0"
;;                :source-shape "_:9fffa06e88c16ff740c337a4a524cfe3"}]}
```

A source is a file path String, or anything else `clojure.java.io/input-stream` accepts. Graphs are Jena `org.apache.jena.graph.Graph`s.

| Function | Does |
|---|---|
| `read-jsonld` | parses JSON-LD sources into one graph, each source with its own blank nodes; a remote context outside `:contexts` throws |
| `read-turtle` | parses one Turtle source |
| `inoculate` | returns the ontology triples pyshacl's `inoculate()` mixes into a data graph |
| `targets-any?` | whether any class in the data is a shape target; when none is, no class-targeted shape checks anything |
| `validate` | validates and returns the report as Clojure data |

### Validating with an ontology

Given an ontology, pyshacl mixes its RDFS and OWL axioms into the data graph before validating, so an `sh:class` constraint can be met by a type the ontology asserts. To validate the same way, union the inoculated triples into the data:

```clojure
(import '(org.apache.jena.graph GraphUtil))

(GraphUtil/addInto data (shacl/inoculate (shacl/read-turtle "ontology.ttl")))
(shacl/validate data shapes)
```

## Command line

```bash
clojure -M:cli --shapes SHAPES.ttl [--ontology ONTOLOGY.ttl] \
               [--context URL=FILE ...] [--format text|edn|json] DATA...
```

DATA files ending in `.ttl` are Turtle and the rest are JSON-LD. The exit status is `0` when the data conforms, `1` when it does not, and `2` on a usage or input error. From a clone of this repository:

```bash
clojure -M:cli --shapes test/resources/fixtures/person-shapes.ttl \
  --context https://example.org/person/context=test/resources/fixtures/person-context.jsonld \
  test/resources/fixtures/alice.jsonld test/resources/fixtures/bob.jsonld
```

```
violation	http://example.org/bob	-	http://www.w3.org/ns/shacl#OrConstraintComponent	http://example.org/bob	Or at focusNode <http://example.org/bob>
violation	http://example.org/bob	http://example.org/address	http://www.w3.org/ns/shacl#NodeConstraintComponent	_:a277008bdc5f001ee6d5bb0e897e8021	Node[<http://example.org/AddressShape>] at focusNode _:Ba277008bdc5f001ee6d5bb0e897e8021
violation	http://example.org/bob	http://example.org/age	http://www.w3.org/ns/shacl#MaxInclusiveConstraintComponent	151	Data value 151 is not less than or equal to 150
does not conform: 3 result(s)
```

Each text line is severity, focus, path (`-` at node level), component, value and message, separated by tabs.

## API documentation

The full API reference is embedded in the JAR as Markdown via [codox-md](https://github.com/dcj/codox-md), and is discoverable at runtime through [clj-doc-browse](https://github.com/dcj/clj-doc-browse):

```clojure
(require '[doc.browse :as docs])

(docs/print-doc "shacl.core")
```

## License

[MIT License](LICENSE). Copyright (c) 2026 Clark Communications Corporation
