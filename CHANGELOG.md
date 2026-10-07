<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# Changelog

Notable changes to [`com.dcj/clj-shacl`](https://clojars.org/com.dcj/clj-shacl). The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- `validate` results carry `:value-node`, the Jena node behind `:value`, so a literal's language tag and datatype and the difference between a literal and an IRI survive.

## [0.1.0] - 2026-10-07

### Added

- `shacl.core`, on Apache Jena 6.2.0: `read-jsonld` parses JSON-LD sources into one graph, serving remote contexts only from a caller-supplied `:contexts` map; `read-turtle`; `inoculate`, a port of pyshacl 0.40.1's ontology mixin, including the `owl:NamedIndividual` deep clone; `targets-any?`; and `validate`, which returns the SHACL report as Clojure data.
- `shacl.cli`: validate JSON-LD and Turtle files against a shapes file, with an optional ontology and context map, printing text, EDN or JSON. Exits `0` when the data conforms, `1` when it does not, `2` on a usage or input error.

[0.1.0]: https://github.com/dcj/clj-shacl/releases/tag/v0.1.0
