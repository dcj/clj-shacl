<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# Releasing clj-shacl

Land every change, update `CHANGELOG.md` and the README Install coordinate, set `version` in `build.clj` to the release version (no `-SNAPSHOT`), commit, then:

```bash
clojure -T:build tag
CLOJARS_PASSWORD="$CLOJARS_TOKEN_COM_DCJ" clojure -T:build release
```

`tag` tags HEAD as `v<version>`. `release` runs `ci` (tests, jar, artifact checks) and then `deploy`. `CLOJARS_TOKEN_COM_DCJ` is the Clojars deploy token scoped to `com.dcj/*`; `deps-deploy` reads only `CLOJARS_PASSWORD`, so the token is passed inline.

Afterwards, bump `version` to the next `-SNAPSHOT`, commit, and push the branch and the tag. Then publish the GitHub release from that version's CHANGELOG section:

```bash
awk '/^## \[X.Y.Z\]/{f=1; next} /^## \[/{f=0} f' CHANGELOG.md > /tmp/notes.md
gh release create vX.Y.Z --title vX.Y.Z --notes-file /tmp/notes.md --latest
```

## What `ci` refuses

`ci` fails unless the jar carries `META-INF/LICENSE`, the CHANGELOG among the docs, a pom naming the project description and url, and build provenance naming HEAD.

## What `deploy` refuses

`deploy` publishes whatever is in `target/` and does not build it. The jar's `build-provenance.edn` records the commit it was built from, and `deploy` checks it against HEAD.

| Refusal | Fix |
|---|---|
| version is a `-SNAPSHOT` | set a release version in `build.clj` |
| working tree has uncommitted changes | commit or stash |
| `src/` or `resources/` holds a git-ignored file | remove it, or commit it; `ci` copies those directories whole |
| no tag `v<version>` | `clojure -T:build tag` |
| tag does not point at HEAD | tag the commit you are shipping |
| no build provenance in the jar | `clojure -T:build ci` |
| jar was built at another version or commit | `target/` is stale; run `ci` |
| jar was built from a dirty tree | commit, then run `ci` |
| `CLOJARS_PASSWORD` unset | pass the token as above |

`:force true` skips these checks.

## Quality gates

`release` runs the tests. Run the rest by hand before tagging:

```bash
clj-kondo --lint src test dev
markdownlint-cli2 README.md CHANGELOG.md 'doc/*.md'
reuse lint
```

## What lands in the jar

Beyond the sources: the MIT notice at `META-INF/LICENSE`; Markdown API docs, the `doc/` guides and `CHANGELOG.md` under `docs/com.dcj/clj-shacl/`; and build provenance at `META-INF/com.dcj/clj-shacl/build-provenance.{edn,json}`.
