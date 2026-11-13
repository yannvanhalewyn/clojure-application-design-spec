# Clojure Application Design Specification

An opinionated specification for designing, scaffolding and extending Clojure
applications and multi-service monorepos, written to be handed to AI coding
agents as binding instructions and read by humans as design rationale.

Core priority: **navigability first**. Plain data and functions, explicit
composition roots, and keys you can grep from config to code.

## Contents

| Path                         | What                                                                 |
|------------------------------|----------------------------------------------------------------------|
| [`SPEC.md`](SPEC.md)         | The specification (RFC 2119 MUST/SHOULD/MAY), sections §0–§12        |
| [`index.html`](index.html)   | Interactive guide: decision helpers, namespace and route-data explorers, scaffolding checklist |
| [`artifacts/`](artifacts/)   | Starter files to copy into a new project                             |

Topics: monorepo layout (and when to consider Polylith), namespace structure,
namespaced keywords, Malli domain models, Aero + Integrant without derived
keys, Reitit route tables with route-data middleware, hypermedia-first UI
(HTMX), functions and data over multimethods, `tools.*` instead of `utils`,
cljfmt in Tonsky style, REPL workflow with Portal.

## Artifacts

| File                          | Purpose                                                        |
|-------------------------------|----------------------------------------------------------------|
| `cljfmt.edn`                  | Formatting: 2-space lists, aligned collections (Tonsky style)  |
| `.gitignore`                  | Build output, tooling caches, REPL state, local secrets        |
| `.editorconfig`               | 2 spaces, LF, final newline                                    |
| `.clj-kondo/config.edn`       | Consistent aliases, sorted requires, `:lint-as`                |
| `.lsp/config.edn`             | clojure-lsp using the same cljfmt config                       |
| `deps.edn`                    | Starter dependencies and aliases                               |
| `bb.edn`                      | Tasks: `nrepl`, `fmt`, `fmt:check`, `lint`, `test`, `uber`      |
| `build/build.clj`             | One uberjar per service                                        |
| `dev/user.clj`                | REPL entry point: `go`/`reset`/`halt` and Portal helpers       |
| `env.example`                 | Copy to `.env`: committed, non-secret defaults                 |

## Using it with an agent

Point the agent at `SPEC.md` and the `artifacts/` directory, for example:

> Scaffold a new service following `SPEC.md`. Copy the files from
> `artifacts/` and follow the checklist in §12.

## Viewing the guide

Open `index.html` in a browser. It is a single self-contained file with no
external dependencies.

To publish it on GitHub Pages: Settings → Pages → Deploy from a branch →
`main`, folder `/ (root)`. The empty `.nojekyll` file makes Pages serve the
files as-is instead of running them through Jekyll.
