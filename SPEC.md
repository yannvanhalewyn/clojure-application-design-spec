# Clojure Application Design Specification

A specification for designing, scaffolding, and extending Clojure applications
and multi-service monorepos. It is written to be handed to AI coding agents as
binding instructions, and to be read by humans as a design rationale.

The keywords MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are used as in RFC 2119.

Placeholders used throughout:

| Placeholder        | Meaning                                                   | Example                       |
|--------------------|-----------------------------------------------------------|-------------------------------|
| `com.company`      | Reverse-domain root of the organization                   | `com.acme`                    |
| `<app>`            | Application name (single-app repos)                       | `billing`                     |
| `service-NN-name`  | Service name in a monorepo, numbered by introduction      | `service-01-website`          |
| `<entity>`         | Domain entity / model name, singular                      | `product`, `user`, `invoice`  |
| `<feature>`        | User-facing feature that may span entities                | `onboarding`, `team`          |

Companion files:

- `index.html` — interactive guide to this document (decision helpers,
  namespace explorer, route-data explorer, scaffolding checklist).
- `artifacts/` — dotfiles and starter configs referenced below
  (`cljfmt.edn`, `.gitignore`, `.editorconfig`, `.clj-kondo/config.edn`,
  `.lsp/config.edn`, `deps.edn`, `bb.edn`, `build/build.clj`, `dev/user.clj`,
  and `env.example` which is copied to `.env`).

---

## 0. Guiding principle: navigability first

Every rule below derives from one priority order:

1. **Navigability and traceability.** From any call site, a human or an agent
   MUST be able to reach the implementation with go-to-definition or a single
   grep. Prefer a direct var reference over a keyword dispatched through a
   registry; prefer an explicit list over load-order side effects.
2. **Plain data and functions.** Model behavior as functions over maps and
   vectors. Data is inspectable at the REPL, printable, diffable, and
   composable with `clojure.core`.
3. **Boring, explicit composition.** A small number of composition roots
   (system config, router, layout) list their parts explicitly.
4. **Abstraction only when it removes real repetition** and still expands to
   data you could have written by hand.

When two rules conflict, the one closer to the top of this list wins.

---

## 1. Repository and monorepo layout

### 1.1 Shape

One repository, one `deps.edn`, many services. Services are namespaces, not
separate projects.

```
.
├── deps.edn                 ; single dependency graph, aliases for extras
├── bb.edn                   ; task runner (repl, fmt, lint, test, uber)
├── cljfmt.edn               ; formatting (see §10)
├── .clj-kondo/config.edn    ; linting
├── .lsp/config.edn          ; clojure-lsp
├── .env                     ; committed, non-secret defaults
├── .env.local               ; gitignored, secrets and personal overrides
├── build/build.clj          ; tools.build: one uberjar per service
├── dev/
│   ├── user.clj             ; (go) (reset) (halt) REPL entry points
│   ├── dev_opts.edn.example ; committed template
│   └── dev_opts.edn         ; gitignored personal dev preferences
├── resources/
│   └── system/
│       ├── system.edn       ; composition root: which components per service
│       ├── components/*.edn ; one file per shared component config
│       └── services/*.edn   ; one file per service config
├── src/com/company/
│   ├── system.clj           ; run-system!, shared ig methods
│   ├── config.clj           ; aero readers + read-config
│   ├── env.clj              ; .env loading
│   ├── request.clj          ; request accessors shared by services
│   ├── <entity>.cljc        ; shared domain models
│   ├── <concern>.clj        ; shared concerns (mailer, kafka, redis, auth …)
│   ├── tools/               ; domain-agnostic helpers (see §9)
│   ├── views/               ; shared UI components
│   ├── service_01_website/
│   ├── service_02_dashboard/
│   └── service_03_worker/
└── test/com/company/…       ; mirrors src
```

### 1.2 Services

- Every service MUST live under `com.company.service-NN-name.*`. The number is
  assigned once, in order of introduction, and never reused. It keeps services
  sorted in file trees and makes the service of any namespace obvious from its
  name.
- Every service MUST have:
  - `main.clj` — `-main` that calls the shared `run-system!` with the
    service's root key and `:production` profile. Nothing else.
  - `system.clj` — Integrant methods for service-specific keys and
    `require`s of every namespace whose `defmethod`s no config key names
    (multimethod cases, §8.3). Component namespaces load from their keys
    (§3.4).
  - `server.clj` (HTTP services) — builds the router by concatenating feature
    route tables and declares the middleware stack (§6).
  - `resources/system/services/<name>.edn` — the service's system config.
- Code used by more than one service MUST move to `com.company.<concern>`
  (outside any `service-*` namespace). A service namespace MUST NOT require
  another service's namespaces.

```clojure
(ns com.company.service-01-website.main
  (:require
    [com.company.service-01-website.system]
    [com.company.system :as system])
  (:gen-class))

(defn -main
  [& _args]
  (system/run-system! [:service/website] :production))
```

### 1.3 deps.edn

- Start with a single top-level `:deps` map grouped by comments
  (`;; System and configuration`, `;; Web`, `;; Logging` …).
- Use aliases for:
  - `:dev` — `dev/`, `test/`, REPL tooling (integrant/repl, portal …).
  - `:test` — test runner.
  - `:build` — tools.build.
  - `:cljs` — shadow-cljs, only when a service ships ClojureScript.
  - `:service/<name>` — heavy libraries only one service needs (document
    parsers, ML runtimes, cloud SDKs). The build passes the alias when
    building that service's uberjar; `:dev` includes all service aliases.
- MUST NOT pin a library in more than one place. Versions live in `deps.edn`.

See `artifacts/deps.edn`.

### 1.4 Should we use Polylith?

**Default: no.** Start with the layout above. Re-evaluate when the signals
below appear.

| Signal                                                        | Single deps.edn + aliases | Polylith |
|---------------------------------------------------------------|---------------------------|----------|
| 1–6 deployables, one team                                     | ✅ fits                    | overhead |
| Services need *conflicting* versions of the same library      | ❌                         | ✅        |
| Deployables' dependency trees diverge heavily (image size, CVE surface) | aliases mitigate | ✅ |
| Several teams need enforced component boundaries              | discipline only           | ✅ enforced |
| Incremental CI (test only what changed) is a bottleneck       | manual                    | ✅ built-in |

Costs of Polylith that matter for this spec: every component gets an
`interface` namespace that delegates to `impl` — one extra hop on every
go-to-definition, which conflicts with §0; a non-standard directory layout and
tool; more `deps.edn` files to keep in sync.

The intermediate step before Polylith is `:service/<name>` aliases (§1.3) plus
a `build.clj` that builds each service with only its alias. Move to Polylith
only when two or more rows in the table above are ❌ for the current setup.

---

## 2. Namespace structure

### 2.1 Pattern

```
com.company.<app-or-service>.<feature|concern|component|entity>.<layer>.*
```

Layers:

| Layer            | Contains                                                          | May require                          |
|------------------|-------------------------------------------------------------------|--------------------------------------|
| `<entity>` (cljc)| Malli schemas, constructor `make`, pure predicates & transforms   | other models, `tools.*`              |
| `<entity>.db`    | Queries and writes for that entity                                | model, db concern                    |
| `<entity>.routes`| Handlers + route table (`routes` fn)                              | model, `.db`, `.views`, `request`    |
| `<entity>.views` | Pure hiccup functions of props maps                               | model, shared `views.*`, `tools.*`   |
| `<entity>.views.<part>` | Larger sub-views (`views.edit-modal`, `views.list`)        | same as `.views`                     |
| `<feature>.*`    | Same layers for features that span entities (`onboarding.routes`) | models it orchestrates               |

Example for a dashboard service:

```
com.company.service-02-dashboard.main
com.company.service-02-dashboard.system
com.company.service-02-dashboard.server            ; router + middleware stack
com.company.service-02-dashboard.views.layout      ; page shell, sidebar
com.company.service-02-dashboard.views.modal       ; modal component + events
com.company.service-02-dashboard.product           ; model (cljc)
com.company.service-02-dashboard.product.db
com.company.service-02-dashboard.product.routes
com.company.service-02-dashboard.product.views
com.company.service-02-dashboard.product.views.edit-modal
com.company.service-02-dashboard.team.routes       ; feature spanning user + role
com.company.service-02-dashboard.team.views
com.company.user                                   ; model shared by services
com.company.mailer                                 ; shared concern / component
com.company.tools.htmx                             ; domain-agnostic tool
```

### 2.2 Rules

- Model namespaces MUST be `.cljc` unless they need JVM interop, so the same
  schemas and predicates run in the browser.
- The model namespace is singular (`product`) and its layers hang below it
  (`product.db`, `product.routes`, `product.views`). Aliases follow:
  `[… .product :as product]`, `[… .product.db :as product.db]`.
- Data flows one way: `routes → db/views → model → tools`. Models MUST NOT
  require `db`, `routes`, or `views`. Views MUST NOT require `routes` or `db`.
- `db` functions take a database value for reads and a connection/node for
  writes. Side-effecting functions end with `!` (`create!`, `delete!`).
  Functions returning transaction data (`…-tx-ops`) are pure; handlers combine
  them and execute once.
- Split a namespace when it mixes layers or exceeds ~600 lines — not before.
- Namespace docstrings SHOULD explain the entity/feature in one or two
  sentences.

### 2.3 Aliases

- Alias = last segment, or `parent.child` when the last segment is ambiguous
  (`product.db`, `team.routes`, `tools.malli`).
- Fixed aliases, enforced by clj-kondo `:consistent-alias`:
  `clojure.string :as str`, `clojure.set :as set`, `malli.core :as m`,
  `malli.util :as mu`, `integrant.core :as ig`, `reitit.ring :as ring`.
- `:refer` only for very high-frequency names (`[com.company.i18n :refer [tr]]`).
  Never `:refer :all`.
- Use `:as-alias` (Clojure ≥ 1.11) when a namespace is required only to write
  its keywords (`::stream.routes/show`, `::kafka/bucket`). It avoids loading the
  namespace and avoids circular requires.

---

## 3. Namespaced keywords

There are three kinds of keys. Pick the kind before naming the key.

### 3.1 Domain keys — short, stable namespace

Domain attributes use a short namespace equal to the entity name. They are
persisted, sent over the wire, shown in forms, and outlive any refactor.

```clojure
{:user/id          #uuid "…"
 :user/first-name  "Ada"
 :user/email       "ada@example.com"
 :user/roles       [{:role/id :role/admin :domain/id #uuid "…"}]}

{:product/id            #uuid "…"
 :product/name          "Invoices"
 :product/audience-type :product.audience-type/internal}
```

- Enum values are keywords namespaced by `<entity>.<attribute>`:
  `:product.audience-type/internal`, `:stream.online-status/offline`,
  `:order.status/paid`.
- Cross-cutting domain vocabularies get their own short namespace:
  `:role/admin`, `:permission/manage-products`, `:feature/new-onboarding`,
  `:job/type`, `:job/args`.
- Nested entity keys: `:<entity>.<child>/<attr>` (`:data-card.field/name`).
- Destructure with `{:user/keys [first-name last-name]}`.
- MUST NOT use unqualified keys for domain data. MUST NOT encode the namespace
  in the name (`:user-first-name`).

### 3.2 Mechanical keys — fully qualified, move with the code

Keys that exist because of *code* (component config, request decorations,
route data understood by a middleware, UI state, internal maps) MUST be fully
qualified to the namespace that owns them, written with `::`:

```clojure
;; owned by com.company.service-02-dashboard.views.modal
{::modal/is-open true}

;; owned by com.company.kafka
{::kafka/bucket "events" ::kafka/base-properties {…}}

;; route data understood by middleware in com.company.auth
{::auth/permissions [:permission/manage-products]}

;; structured permissions: keys and actions owned by com.company.permission,
;; the resource is domain vocabulary (§3.1)
{::auth/permissions [{::permission/resource :resource/products
                      ::permission/action   ::permission/update}]}

;; route data understood by layout middleware
{::layout/page :sidebar}

;; config placed on the request by the system, read by the static-assets ns
(::static-assets/files req)
(::feature-flags/enabled-features req)
(::error-pages/error-handler config)

;; internal map keys of a namespace
{::env/merged {…} ::env/main {…}}
```

Why: when the owning namespace is renamed or moved, clojure-lsp rename
updates every `::alias/key`; `find references` on the keyword lands on every
producer and consumer; the key cannot collide with another module.

In EDN (system config) `::` is unavailable — write the full form
(`:com.company.static-assets/files`). The Clojure side still reads it as
`::static-assets/files`.

`::alias/key` is shorthand, not a different key. With
`[com.company.auth :as-alias auth]` and `[com.company.permission :as permission]`
the structured permissions above read as:

```clojure
{:com.company.auth/permissions
 [{:com.company.permission/resource :resource/products
   :com.company.permission/action   :com.company.permission/update}]}
```

This is also how they are written in EDN, printed in Portal, and found by grep.

### 3.3 Vocabulary keys — short namespace, documented once

An in-house DSL used in hundreds of call sites MAY use a short, unique,
documented namespace, the same way libraries use `:xt/id` or `:db/id`:

```clojure
{:res/hiccup   [:div …]          ; response body (see §6.4)
 :htmx/trigger [modal/CLOSE_EVENT]
 :htmx/push-url "/products"}
```

The owning namespace MUST document the full vocabulary in its namespace
docstring and SHOULD validate it with a Malli schema. Do not invent new short
namespaces casually; default to §3.2.

### 3.4 System (Integrant) keys

The key written in the config is the key of its `defmethod`. Grep a key from
`system.edn` and you land on its `ig/init-key`. Therefore:

- MUST NOT `derive` Integrant keys, use composite keys (`[:a/x :b/y]`, which
  `derive` under the hood), or `#ig/ref`/`#ig/refset` a parent key (§8.5).
- **Component keys** — MUST be fully qualified to the namespace holding the
  `defmethod`s, for components a system has once:
  `:com.company.kafka/producer`, `:com.company.redis/client`,
  `:com.company.tools.db-scheduler/db-store`. The key names the namespace
  that registers its methods, so `ig/load-namespaces` (§5.4) requires it
  whenever the key is in the config: for `:com.company.kafka/producer` it
  loads `com.company.kafka` (and `com.company.kafka.producer` if it exists).
  Adding a component to the config is enough to load its code; no `require`
  in `system.clj` can be forgotten. A key qualified to anything else
  (`:kafka/producer`, `:system/kafka`) only works while some other namespace
  happens to require the implementation.
- **Service instance keys** — `:service/<name>` and `:service.<name>/<thing>`,
  for components that exist per service or more than once:
  `:service/dashboard`, `:service.dashboard/http-server`, `:service.dashboard/db`.
  Each has its own `defmethod` in the service's `system.clj`: one line that
  calls the shared plain function. `ig/load-namespaces` cannot find these
  methods from the key; `main.clj` and `go` require the service's `system`
  namespace instead (§1.2, §5.6).

```clojure
(defmethod ig/init-key :service.dashboard/db [_ config]
  (db/start-node config))

(defmethod ig/halt-key! :service.dashboard/db [_ node]
  (db/stop-node node))

(defmethod ig/init-key :service.dashboard/session-store [_ config]
  (session-store/make config))
```

- Plain configuration (option maps, URLs, ports, feature flags) is not an
  Integrant key. Inline it where it is used with Aero `#ref` or `#include`.

### 3.5 Route names

Route names are mechanical keys owned by the routes namespace: `::index`,
`::show` inside `product.routes`, referenced elsewhere as
`::product.routes/show` via `:as-alias`.

---

## 4. Domain models and schemas

### 4.1 Every domain map and key has a schema

- Each entity namespace MUST define Malli schemas for:
  - `Id` — the identifier schema.
  - `<Entity>` — the full shape as read from storage (persisted keys first,
    then `{:optional true}` keys that are enriched for the UI, separated by a
    comment).
  - Enum schemas for enumerated attributes.
  - `<Entity>CreateParams` / `<Entity>UpdateParams` — derived with
    `malli.util` (`mu/select-keys`, `mu/optional-keys`, `mu/merge`), never
    re-typed by hand.
- Schemas are PascalCase vars wrapped in `m/schema`, with a docstring starting
  with `(schema)`.
- Reference schemas by var (`product/Product`), not by registry keyword.
  Vars are navigable; keyword registries are not. The global registry is only
  for primitive custom types (`:base-url`, `:secret`, `:time/instant`) and is
  set once in `com.company.tools.malli`.

```clojure
(ns com.company.service-02-dashboard.product
  "A product groups data streams for a business audience."
  (:require
    [com.company.service-02-dashboard.domain :as domain]
    [malli.core :as m]
    [malli.util :as mu]))

(def Id
  "(schema) Identifier of a product."
  (m/schema :uuid))

(def AudienceType
  "(schema) Who the product is published to."
  (m/schema
    [:enum
     :product.audience-type/internal
     :product.audience-type/business
     :product.audience-type/personal]))

(def Product
  "(schema) A product as read from the database."
  (m/schema
    [:map
     [:product/id Id]
     [:product/name [:string {:min 1}]]
     [:product/description :string]
     [:product/domain domain/Domain]
     [:product/audience-type AudienceType]
     ;; Optionally enriched for the UI
     [:product/stream-ids {:optional true} [:set :uuid]]]))

(def CreateParams
  "(schema) Form params accepted when creating a product."
  (mu/select-keys Product [:product/name :product/description]))

(def UpdateParams
  "(schema) Form params accepted when updating a product."
  (mu/optional-keys
    (mu/select-keys Product [:product/name :product/description])))

(defn make
  "Returns a new product with generated id and defaults."
  [attrs]
  (assoc (select-keys attrs [:product/name :product/description :product/domain])
    :product/id (random-uuid)
    :product/audience-type :product.audience-type/internal))

(defn internal? [product]
  (= :product.audience-type/internal (:product/audience-type product)))

(defn add-stream [product stream-id]
  (update product :product/stream-ids (fnil conj #{}) stream-id))
```

### 4.2 Where schemas are enforced

| Boundary                      | Mechanism                                                   |
|-------------------------------|-------------------------------------------------------------|
| HTTP params                   | Reitit `:parameters` with `reitit.coercion.malli` (§6)      |
| System config                 | `ig/assert-key` + a throwing Malli coercer (§5.4)           |
| External API payloads         | `m/coerce` at the client namespace edge                     |
| Persisted documents           | Constructor `make` + tests; optional validate in `db` writes |
| Internal function arguments   | Not validated at runtime; schemas serve as documentation    |

### 4.3 Model functions

- Constructors: `make`. Predicates: `internal?`, `joined?`. Derivations:
  `full-name`, `domain-id`. Updates return the new entity (`add-stream`) or an
  attribute map to merge (`…-attrs`).
- Model functions are pure. No database, no request, no clock — pass `now` in,
  or isolate the clock in `make`.
- Constants: `SCREAMING_SNAKE_CASE` (`MAX_UPLOAD_BYTES`, `PAGE_CONTAINER_ID`).

---

## 5. System: Aero + Integrant

### 5.1 Responsibilities

| Library   | Job                                                                |
|-----------|--------------------------------------------------------------------|
| Aero      | Read EDN config: profiles, env vars, secrets, includes, refs       |
| Integrant | Build the dependency graph, start/stop components in order         |
| Malli     | Validate each component's config before start (`ig/assert-key`)   |

### 5.2 Config composition

`resources/system/system.edn` is the single composition root. A custom
`#service` reader merges a block only when that service is being started:

```clojure
#merge
 [#include "system/components/nrepl.edn"

  #service [:service/website
            #include "system/components/static-assets.edn"
            #include "system/services/website.edn"]

  #service [:service/dashboard
            #include "system/components/db.edn"
            #include "system/components/http-client.edn"
            #include "system/components/redis.edn"
            #include "system/components/static-assets.edn"
            #include "system/services/dashboard.edn"]

  #service [:service/worker
            #include "system/components/db.edn"
            #include "system/components/kafka.edn"
            #include "system/services/worker.edn"]]
```

Service file — root key refs the server, the handler config lists every
dependency the request needs:

```clojure
{:service/dashboard
 {:http-server #ig/ref :service.dashboard/http-server}

 :service.dashboard/http-server
 {:jetty/options #ref [:service.dashboard.jetty/options]
  :handler       #ig/ref :service.dashboard/handler}

 :service.dashboard.jetty/options
 {:port   #long #or [#env DASHBOARD_SERVICE_PORT 3002]
  :join?  false}

 :service.dashboard/handler
 {:com.company.db/node                       #ig/ref :service.dashboard/db
  :com.company.static-assets/files           #ig/ref :com.company.static-assets/files
  :com.company.feature-flags/enabled-features #include "system/components/enabled-features.edn"
  :com.company.mailer/mailer                 #profile {:dev  #ig/ref :service.dashboard/browser-mailer
                                                       :test #ig/ref :service.dashboard/memory-mailer
                                                       :production #ig/ref :service.dashboard/smtp-mailer}
  :com.company.error-pages/error-handler     #profile {:default :generic :dev :stacktrace}}}
```

### 5.3 Aero readers

Defined once in `com.company.config` (Aero readers are a library extension
point, so `defmethod` is correct here, §8):

| Reader            | Purpose                                                              |
|-------------------|----------------------------------------------------------------------|
| `#env`            | Value from merged env (process env, `.env`, `.env.local`)           |
| `#env/local`      | Secret env var; throws if it is defined in the committed `.env`     |
| `#secret`         | Wrap in a `Secret` type that prints as `***`; unwrap with `secret/value` at use site |
| `#ig/ref`         | Integrant reference                                                  |
| `#service`        | Conditionally merge maps for the services being started             |
| `#profile`, `#or`, `#ref`, `#include`, `#merge`, `#long`, `#boolean`, `#join` | Aero built-ins |
| `#and`, `#not`    | Feature-gated config (`#and [#env FEATURE_X #ig/ref :…]`)            |

Env precedence (later wins): process env → `.env` → `.env.local` → explicit
overrides passed to `read-config`. `.env` is committed and MUST NOT contain
secrets; `.env.local` is gitignored.

### 5.4 Integrant methods

- `defmethod ig/init-key` bodies are one line that delegates to a plain,
  documented function in the owning namespace, so the component can be started
  from the REPL or a test without Integrant:

```clojure
(ns com.company.kafka
  (:require
    [com.company.tools.malli :as tools.malli]
    [integrant.core :as ig]))

(defn start-producer
  "Starts a Kafka producer for `config`. Close with `stop-producer`."
  [config]
  …)

(defn stop-producer [producer]
  …)

(def ^:private assert-producer-config
  (tools.malli/throwing-coercer
    [:map
     [::base-properties [:map-of :string :string]]
     [::bucket :string]]
    nil
    "Invalid Kafka producer config: %s"))

(defmethod ig/assert-key ::producer [_ config]
  (assert-producer-config config))

(defmethod ig/init-key ::producer [_ config]
  (start-producer config))

(defmethod ig/halt-key! ::producer [_ producer]
  (stop-producer producer))
```

- `defmethod`s for a key live in the namespace the key is qualified with. For
  `:service.*` keys they live in that service's `system.clj`.
- No pass-through keys. Plain config is inlined with Aero `#ref`/`#include`.
  The service root key (`:service/dashboard`), which only pulls the service's
  components into `ig/init`, gets an explicit one-line method:
  `(defmethod ig/init-key :service/dashboard [_ config] config)`.
- `com.company.tools.integrant/init-system!` wraps `ig/init` and halts the
  partially started system when a component throws.
- `run-system!` calls `ig/load-namespaces` on the config before `ig/expand`
  and `ig/init`, so every component namespace is loaded from its key (§3.4).
- `run-system!` logs the resolved config (secrets print masked) before init.

### 5.5 Handler receives the system

The HTTP handler's config is merged onto every request by a middleware at the
root of the router (`wrap-merge`). Handlers never reach into the system with
raw keys; `com.company.request` exposes accessors:

```clojure
(ns com.company.request
  (:require
    [com.company.db :as db]
    [reitit.core :as r]))

(defn db-node [req] (::db/node req))
(defn db [req] (db/db (db-node req)))

(defn route-url
  "Path for the named route, merging current path params with `params`."
  ([req route-name] (route-url req route-name {}))
  ([req route-name params]
   (-> (::r/router req)
     (r/match-by-name! route-name (merge (:path-params req) params))
     (r/match->path))))
```

### 5.6 Development

- `dev/user.clj` (template: `artifacts/dev/user.clj`) exposes `(go)`,
  `(reset)`, `(halt)` via `integrant.repl`, starting the services listed under
  `:go-services` in `dev/dev_opts.edn`, and the Portal helpers of §11.1.
- A `system-namespaces` map in `user.clj` maps each service root key to its
  `system` namespace; `go` requires them, then calls `ig/load-namespaces` for
  the component namespaces, before init.
- `dev/dev_opts.edn` (gitignored) holds personal preferences; a committed
  `dev_opts.edn.example` documents every option. A `#dev-opt` Aero reader,
  registered only in `user.clj`, lets dev config read it.
- Profiles: `:dev`, `:test`, `:production`. Tests start a system with the
  `:test` profile through a `with-test-system` macro (lint it as `let`).

---

## 6. HTTP: Reitit route tables, route data, middleware

### 6.1 Route tables are data

- Each feature/entity exposes `(defn routes [] [...])` in its `routes`
  namespace: a plain vector of reitit routes. A service's `server.clj`
  `concat`s them. Grep for a path → find the table → jump to the handler.
- Handlers are plain `defn`s taking `req` and returning a Ring response or a
  response intent (§6.4). Reference them as vars (`#'show`) so re-evaluating a
  handler at the REPL takes effect without rebuilding the router.
- Every route MUST have a `:name` (§3.5). Links are built with
  `request/route-url`, never by string concatenation.
- Parameters are declared with `:parameters` and model schemas; coercion is
  reitit's.
- Cross-cutting behavior is declared as **route data** and implemented by a
  **compiled middleware** (§6.2), never by wrapping individual handlers by hand.

```clojure
(ns com.company.service-02-dashboard.product.routes
  (:require
    [com.company.auth :as-alias auth]
    [com.company.request :as req]
    [com.company.service-02-dashboard.product :as product]
    [com.company.service-02-dashboard.product.db :as product.db]
    [com.company.service-02-dashboard.product.views :as product.views]
    [com.company.service-02-dashboard.views.layout :as-alias layout]
    [com.company.service-02-dashboard.views.modal :as modal]
    [com.company.tools.response :as response]))

(defn- current-product [req]
  (or (product.db/find-by-id (req/db req) (-> req :parameters :path :id))
      (response/not-found! "Product not found" {:id (-> req :parameters :path :id)})))

(defn index [req]
  {:res/hiccup
   (product.views/index
     {:products     (product.db/all (req/db req))
      :product-url  #(req/route-url req ::show {:id (:product/id %)})
      :new-url      (req/route-url req ::new)})})

(defn show [req]
  (let [product (current-product req)]
    {:res/hiccup
     (product.views/show
       {:product  product
        :edit-url (req/route-url req ::edit-modal {:id (:product/id product)})})}))

(defn update! [req]
  (let [id (-> req :parameters :path :id)]
    (product.db/update! (req/db-node req) id (-> req :parameters :form))
    {:res/hiccup   (product.views/show {:product (current-product req)})
     :htmx/trigger [modal/CLOSE_EVENT]}))

;; create!, new-form and edit-modal follow the same shape and are omitted.

(defn routes []
  [["/products"
    {:name ::index
     :get  {:handler #'index
            ::layout/page :sidebar}
     :post {:handler #'create!
            :parameters {:form product/CreateParams}
            ::auth/permissions [:permission/manage-products]}}]
   ["/products/new"
    {:name ::new
     :get  {:handler #'new-form
            ::layout/page :sidebar
            ::auth/permissions [:permission/manage-products]}}]
   ["/products/:id"
    {:name ::show
     :parameters {:path [:map [:id product/Id]]}
     :get   {:handler #'show
             ::layout/page :sidebar}
     :patch {:handler #'update!
             :parameters {:form product/UpdateParams}
             ::auth/permissions [:permission/manage-products]}}]
   ["/products/:id/edit-modal"
    {:name ::edit-modal
     :parameters {:path [:map [:id product/Id]]}
     :get #'edit-modal}]])
```

Reitit merges route data from parent to child and from path level to method
level, so a key declared on a parent applies to all children.

### 6.2 Route-data middleware

Each route-data key has exactly one middleware that understands it, defined in
the namespace that owns the key, using reitit's `:compile` so that it mounts
only on routes that declare the key and does its work at router-build time:

```clojure
(ns com.company.auth
  (:require
    [com.company.error-pages :as error-pages]
    [com.company.request :as req]
    [com.company.tools.htmx :as htmx]))

(def permissions-middleware
  "Authorizes the current user against `::permissions` declared in route data.
  Entries are `:permission/x` or `[:permission/x ctx-fn]`; `ctx-fn` receives the
  request. All entries must pass. Mounts only on routes that declare it."
  {:name ::permissions
   :compile
   (fn [{::keys [permissions]} _opts]
     (when (seq permissions)
       (let [checks (mapv #(if (vector? %) % [% (constantly nil)]) permissions)]
         (fn [handler]
           (fn [req]
             (if (every? (fn [[permission ctx-fn]]
                           (req/authorized? req permission (ctx-fn req)))
                   checks)
               (handler req)
               (if (htmx/client-request? req)
                 {:status 403}
                 (error-pages/unauthorized req))))))))})
```

Rules:

- The router's `:middleware` vector in `server.clj` is the **only** place where
  middleware is installed. Read it top to bottom to know everything a request
  goes through. No global registries, no `register-…!` side effects.
- Route-data keys are fully qualified (`::auth/permissions`,
  `::layout/page`), except reitit's own (`:name`, `:parameters`,
  `:handler`, `:middleware`).
- A middleware that needs no route data is a plain `(fn [handler] …)`.
- Document the accepted shape of the route-data key in the middleware's
  docstring; validate it in `:compile` with `assert` or Malli so typos fail at
  startup, not at request time.

### 6.3 The router: one explicit middleware stack

```clojure
(ns com.company.service-02-dashboard.server
  (:require
    [com.company.auth :as auth]
    [com.company.error-pages :as error-pages]
    [com.company.service-02-dashboard.product.routes :as product.routes]
    [com.company.service-02-dashboard.team.routes :as team.routes]
    [com.company.service-02-dashboard.views.layout :as layout]
    [com.company.tools.response :as response]
    [com.company.tools.ring :as tools.ring]
    [muuntaja.core :as muuntaja]
    [reitit.coercion.malli :as reitit.malli]
    [reitit.ring :as ring]
    [reitit.ring.coercion :as rrc]
    [reitit.ring.middleware.exception :as exception]
    [reitit.ring.middleware.muuntaja :as reitit.muuntaja]
    [reitit.ring.middleware.parameters :as parameters]
    [ring.middleware.cookies :as cookies]))

(defn routes []
  (concat
    (product.routes/routes)
    (team.routes/routes)
    [["/healthz" {:name ::healthz :get (constantly {:status 200 :body "OK"})}]]))

(defn http-handler [config]
  (ring/ring-handler
    (ring/router (routes)
      {:data {:coercion reitit.malli/coercion
              :muuntaja muuntaja/instance
              :middleware
              [(exception/create-exception-middleware
                 (merge exception/default-handlers
                   {::response/not-found response/not-found-handler
                    ::exception/default  (error-pages/make-error-handler config)}))
               cookies/wrap-cookies
               parameters/parameters-middleware
               reitit.muuntaja/format-middleware
               rrc/coerce-exceptions-middleware
               rrc/coerce-request-middleware
               [tools.ring/wrap-merge config]   ; system deps onto request
               auth/session-middleware          ; current user onto request
               response/middleware              ; intents → Ring responses (outer)
               auth/permissions-middleware      ; ::auth/permissions
               layout/page-middleware]}})       ; ::layout/page (inner)
    (ring/create-resource-handler {:path "/"})))
```

Middleware order is the vector order: first entry is outermost.
`response/middleware` sits outside `layout/page-middleware` so layout can wrap
the response intent before it becomes HTML.

### 6.4 Response intents

Handlers return either a Ring response (any map with an integer `:status`) or
a **response intent**: a map with exactly one body key and optional htmx
header keys. `com.company.tools.response/middleware` turns intents into Ring
responses.

| Key                | Produces                                              |
|--------------------|-------------------------------------------------------|
| `:res/hiccup`      | `text/html` from hiccup                               |
| `:res/html`        | `text/html` from a string                             |
| `:res/json`        | `application/json`                                    |
| `:res/redirect`    | 303 redirect to URL (or `[status url]`)               |
| `:res/stream`      | streamed body `{:input-stream :content-type …}`       |
| `:res/no-content`  | 204                                                   |
| `:htmx/trigger`    | `HX-Trigger` header (string, vector, or event map)    |
| `:htmx/push-url`   | `HX-Push-Url`                                         |
| `:htmx/redirect`   | `HX-Redirect`                                         |
| `:htmx/refresh`    | `HX-Refresh: true`                                    |

Intents keep handlers pure and testable: call `(show req)` at the REPL and see
data, not bytes.

### 6.5 Errors and loading

- Load-or-404 with a plain function, not a framework feature:
  `(or (product.db/find-by-id db id) (response/not-found! "…" {:id id}))`.
  `not-found!` throws `ex-info` with `:type ::response/not-found`, mapped to a
  404 by the exception middleware.
- Coercion failures return 400 (or re-render the form with errors via a route
  data key such as `::form/on-invalid`).
- Prefer a `let` in the handler over declarative "injection" DSLs: it reads
  top-down and every step is navigable.

### 6.6 Macros over route data

A handler-definition macro MAY be used to remove real repetition, but it
MUST expand to the same plain route data a human could write, MUST NOT
register anything globally, and MUST be linted with `:lint-as`. Prefer no
macro.

---

## 7. Web UI: hypermedia first

### 7.1 Choose the approach

| App profile                                                        | Approach                                                      |
|--------------------------------------------------------------------|---------------------------------------------------------------|
| CRUD, dashboards, forms, back-office, marketing sites (~90%)       | **Server-rendered hiccup + HTMX** (+ hyperscript for tiny local behavior) |
| Mostly hypermedia with one rich widget (editor, canvas, diagram)   | HTMX + a **ClojureScript island** (shadow-cljs) on that element |
| Highly interactive client state, real-time collaborative UI        | **Replicant** (data-driven hiccup rendering, pure functions) |
| Large client-side state, many derived views, existing team know-how | **re-frame**                                                |
| Offline-first / PWA                                                | Replicant or re-frame + service worker + local storage/IndexedDB |
| Native mobile with server-driven screens                           | Hyperview (HXML), same hypermedia principles                 |

Pick the lowest row that the app genuinely needs. Moving from HTMX to an
island is cheap; moving from an SPA back is not.

### 7.2 Hypermedia rules (HATEOAS)

- The server is the source of truth for state and for **which actions are
  available**. Handlers compute URLs for allowed actions and pass them to
  views; a view shows an action iff its URL is non-nil:

```clojure
(defn index [{:keys [products product-url new-url]}]
  [:div
   (when new-url
     [:a {:href new-url :hx-get new-url :hx-target (dom/id layout/PAGE_CONTAINER_ID) :hx-push-url "true"}
      (tr "New product")])
   (for [product products]
     (product-row {:product product :url (product-url product)}))])
```

- One URL serves both full page and fragment: when `HX-Request` is absent the
  `::layout/page` middleware wraps the hiccup in the full page shell;
  otherwise the fragment is returned. Deep links and refresh always work.
- Views are pure functions of a props map. Leaf components MUST NOT receive the
  request, the router, or a db. Only the layout may receive `req`.
- DOM ids, CSS hooks, and event names shared between server and markup are
  constants in the view namespace that owns them, referenced from both sides:
  `layout/PAGE_CONTAINER_ID`, `modal/ROOT_ID`, `modal/CLOSE_EVENT`.
  No repeated string literals.
- Cross-component updates use `HX-Trigger` events (`:htmx/trigger`), not
  out-of-band swaps scattered across handlers. OOB swaps are allowed for
  global regions (alerts, flash messages) via a single helper.
- Small local behavior: hyperscript generated by `tools.hyperscript` helpers
  (`hs/on`, `hs/join`) or a few lines of vanilla JS. If it grows beyond a few
  lines, make it a CLJS island.
- CLJS islands are `*_ui.cljs` controllers attached to server-rendered markup
  with `htmx/on-load`. Shared logic lives in `.cljc` so server and client agree.
- Styling: Tailwind utility classes (+ DaisyUI components); no inline styles
  except dynamic values.

---

## 8. Functions and data over multimethods

### 8.1 Rule

Default to data and functions: a map, a vector, a `case`, or a `cond`.

- Routing and middleware MUST be data: Reitit route tables with handler vars
  and one explicit middleware vector (§6). No multimethods, registries, or
  load-time registration.
- Everything else SHOULD be data and functions. Multimethods are allowed at
  library extension points, in plugin systems, and, if the conditions in §8.3
  hold, for job handlers and rendering by type.

| Allowed `defmethod`                                     | Why                                    |
|---------------------------------------------------------|----------------------------------------|
| `ig/init-key`, `ig/halt-key!`, `ig/assert-key`, …       | Integrant's extension point            |
| `aero/reader`                                           | Aero's extension point                 |
| `print-method`, `print-dup`                             | Clojure's extension point              |
| Plugin systems where third parties add cases            | Openness is the requirement            |
| Job handlers, rendering by type                         | MAY, depending on context (§8.3)       |

| Instead of                                                    | Prefer                                                      |
|---------------------------------------------------------------|-------------------------------------------------------------|
| `defmulti handle-route` dispatching on route keyword          | Reitit route table with handler vars                        |
| Middleware registered into an atom at namespace load          | Explicit middleware vector in `server.clj` + `:compile`     |
| Schemas registered by keyword in a global registry            | Schema vars (`product/Product`)                             |
| Macro that generates handlers + routes + middleware           | Plain `defn` handlers + route data                          |
| `defmulti` over a closed set that one namespace owns          | `case`, or a map of vars, in that namespace                 |

### 8.2 Why

- **Navigability.** Go-to-definition on `#'send-invoice` lands on the code.
  Go-to-definition on a dispatch value lands nowhere.
- **Load order.** Multimethods and registries only contain the cases whose
  namespaces were loaded. A missing `require` silently changes behavior.
- **Inspectability.** A dispatch map can be printed, filtered, and tested; a
  multimethod's table is opaque.
- **Agents.** An AI agent following references in a codebase built from data
  and functions finds the full code path in one pass.

### 8.3 Job handlers and rendering by type

Dispatching jobs on `:job/type`, or rendering on a type key (`:field/type`, a
content block type), MAY use a multimethod. Which form fits depends on where
the cases live:

| Context                                                                     | Use                                    |
|-----------------------------------------------------------------------------|----------------------------------------|
| A handful of cases, all owned by one namespace                              | `case` or a map of vars                |
| Cases belong to their features across many namespaces, and adding one should not touch a central file | `defmulti` MAY                         |
| Callers need the full list of types (admin UI, validation, scheduling)      | A map of vars: the list is the data    |

```clojure
;; Data: the map is the complete list of job types
(def job-handlers
  {"send-invoice.v1" #'invoice.jobs/send-invoice
   "purge-drafts.v1" #'draft.jobs/purge})

;; Multimethod: each feature owns its job types
(defmulti run-job
  "Implementations are required in service-03-worker.system."
  :job/type)

(defmethod run-job :default [job]
  (throw (ex-info "Unknown job type" {:job/type (:job/type job)})))

;; com.company.invoice.jobs
(defmethod jobs/run-job "send-invoice.v1" [job]
  (send-invoice! job))
```

When you use a multimethod:

- Exactly one composition namespace MUST require every implementing namespace
  (the service's `system.clj`, or the namespace that starts the worker), so
  that no case depends on what happened to be loaded.
- The `:default` method MUST throw `ex-info` with the dispatch value, or
  render an explicit fallback. It MUST NOT return `nil` silently.
- Dispatch values MUST be grep-able literals (`"send-invoice.v1"`,
  `:field.type/date`), not computed.
- Method bodies SHOULD be one call to a plain function, which the REPL and
  tests call directly.
- MUST NOT be used for routing or middleware (§8.1).

### 8.4 Protocols

Protocols are for genuine polymorphism over interchangeable implementations
(storage backends: S3, Azure Blob, filesystem, in-memory). The implementation
is selected by the system config (§5), never by runtime type sniffing in
domain code.

### 8.5 Derived keywords

`derive` is a separate decision from multimethods, and a stricter one: there is
no context where we derive keys we own. A hierarchy is global state that is
invisible at the call site. The config says `:service.web/http-server`, the
code says `:system/http-server`, and the link between them is a `derive` call
in a third place that only exists if its namespace was loaded.

- MUST NOT `derive` keys we own: Integrant keys (§3.4) or domain keywords.
- Domain hierarchies become explicit predicates or sets:
  `(def admin-roles #{:role/admin :role/owner})`.
- MAY extend hierarchies a library owns (Duct modules, `:duct/…`).

What `derive` buys in Integrant, and the explicit replacement:

| Argument for `derive`                                         | Replacement                                                  |
|---------------------------------------------------------------|--------------------------------------------------------------|
| Many instances share one implementation without repeating `defmethod`s | One-line `defmethod` per instance calling the shared fn. A few lines of repetition buys a key you can grep. |
| `#ig/ref :system/db` lets a consumer depend on "whatever DB is configured" | Ref the concrete key. Swap implementations per profile with Aero `#profile`, visible in the config. |
| `#ig/refset :system/health-check` collects every instance     | List them explicitly: `[#ig/ref :service.dashboard/db #ig/ref :com.company.redis/client]`. |
| Pass-through config keys derive from `:system/config`         | Not Integrant keys at all: inline with Aero `#ref`/`#include` (§5.4). |
| Libraries ship hierarchies you extend (Duct modules, `:duct/…`) | Allowed: this is the library's extension point, like `ig/init-key` itself. |

Only the last row is a reason to keep `derive`; the others are a few lines of
boilerplate saved at the cost of navigability. Integrant also throws an
ambiguous-key error when a parent ref matches two children, a failure mode
that explicit keys cannot have.

---

## 9. Tools, not utils

### 9.1 Rule

There is no `utils`, `helpers`, `common`, or `misc` namespace. Every function
belongs to a concern, and the namespace name states the concern so that the
function name does not have to.

| Avoid                                  | Prefer                                     |
|----------------------------------------|--------------------------------------------|
| `server-utils/is-htmx-request?`        | `tools.htmx/client-request?`               |
| `utils/format-date`                    | `tools.date/format-iso`                    |
| `utils/->base64`                       | `tools.base64/encode`                      |
| `helpers/dom-id`                       | `tools.dom/id`                             |
| `utils/hs-on-click`                    | `tools.hyperscript/on`                     |
| `common/find-first`                    | `tools.coll/find-first` or `medley.core/find-first` |
| `utils/validate-config`                | `tools.malli/throwing-coercer`             |
| `utils/bearer-token`                   | `tools.ring-request/bearer-token`          |

No `is-` prefixes; predicates end in `?`.

### 9.2 What goes in `tools.*`

`com.company.tools.<concern>` holds domain-agnostic code that wraps a library,
protocol, or format, and that could be extracted into a library unchanged.
A `tools.*` namespace MUST NOT require any domain or service namespace.

Typical set:

| Namespace                 | Concern                                                         |
|---------------------------|-----------------------------------------------------------------|
| `tools.htmx` (cljc)       | `client-request?`, `target`, `boosted?`, header encoding, `on-load` (cljs) |
| `tools.hyperscript`       | `on`, `join`, `when`, `keydown`, `nav-to` — build `_` attributes |
| `tools.dom` (cljc)        | `id` → `"#id"` selectors                                        |
| `tools.hiccup`            | `html` with escaping defaults, `raw`, `classes`                 |
| `tools.response`          | Response intents, `not-found!`, streaming responses             |
| `tools.ring`              | `wrap-merge`, `wrap-assoc`, cookie helpers                      |
| `tools.ring-request`      | `bearer-token`, header accessors                                |
| `tools.reitit`            | Route-table helpers (path-param assertions, named routes)       |
| `tools.malli`             | Default registry, custom types, transformers, `throwing-coercer`|
| `tools.integrant`         | `init-system!` with partial-halt on failure                     |
| `tools.jetty`             | `run-jetty` with sane defaults                                  |
| `tools.date`              | `now`, `plus-days`, formatters                                  |
| `tools.base64`, `tools.jwt`, `tools.crypto`, `tools.random`, `tools.edn`, `tools.file` | as named |
| `tools.<library>`         | Thin wrappers around a Java library (`tools.hikaricp`, `tools.jedis`) |

### 9.3 App-specific shared code

Shared code that knows the domain lives in `com.company.<concern>`:
`request`, `auth`, `permission`, `mailer`, `feature-flags`, `i18n`,
`error-pages`, `views.alert`. Generic collection helpers that are not in
`clojure.core` come from `medley` first; anything left goes in `tools.coll`.

---

## 10. Code style

### 10.1 Formatting: cljfmt, Tonsky style

Formatting follows [Tonsky's "Better Clojure formatting"](https://tonsky.me/blog/clojurefmt/)
and is enforced by `cljfmt` with `artifacts/cljfmt.edn`:

1. Multi-line lists that start with a symbol (i.e. code) are indented **two
   spaces**, always. No special cases per macro.

```clojure
(product.db/update-audience-type! db-node
  product-id
  audience-type)

(when-let [user (current-user req)]
  (log/info "Hello" (user/full-name user)))
```

2. Other multi-line collections (vectors, maps, sets, lists starting with a
   non-symbol) align with the first element.

```clojure
[{:item :one}
 {:item :two}]

{:user/first-name "Ada"
 :user/last-name  "Lovelace"}
```

3. Logical forms whose arguments are on the first line align their arguments
   (`and`, `or`, `=`, `not=`, `str`, `+`, `-`, `comp`, `juxt`, `some-fn`):

```clojure
(or (find-in-cache id)
    (find-in-db db id))
```

4. Trailing whitespace removed, consecutive blank lines collapsed, ns
   references sorted.

Run `cljfmt fix` (or `bb fmt`) before committing; CI runs `cljfmt check`.
Configure the editor (Calva, Cursive, Emacs `clojure-mode` with
`clojure-indent-style 'always-indent`, clojure-lsp formatting) to match.

### 10.2 Namespace form

```clojure
(ns com.company.service-02-dashboard.product.routes
  "Routes for listing, creating and editing products."
  (:require
    [clojure.string :as str]
    [com.company.request :as req]
    [com.company.service-02-dashboard.product :as product]
    [malli.util :as mu])
  (:import
    (java.time Instant)))
```

- `:require` and `:import` on their own line; one lib per line; sorted.
- Imports as lists `(package Class …)`.
- `(set! *warn-on-reflection* true)` in every JVM namespace that uses interop.

### 10.3 Naming

| Kind                    | Convention                         | Example                          |
|-------------------------|------------------------------------|----------------------------------|
| Side effect             | `!` suffix                         | `create!`, `send!`, `reset!`     |
| Predicate               | `?` suffix, no `is-`               | `internal?`, `client-request?`   |
| Conversion              | `->` prefix                        | `->ring`, `->hx-trigger`         |
| Constructor             | `make` / `make-<thing>`            | `product/make`, `make-client`    |
| Component lifecycle     | `start-x` / `stop-x`               | `start-producer`                 |
| Schema                  | PascalCase var                     | `Product`, `UpdateParams`        |
| Constant                | `SCREAMING_SNAKE_CASE`             | `CLOSE_EVENT`, `PAGE_CONTAINER_ID` |
| Private helper          | `defn-`                            | `(defn- current-product …)`      |
| Intentionally unused public var | `^:lsp/allow-unused`       | REPL helpers, public API         |

### 10.4 Documentation and REPL

- Public functions with non-obvious behavior have docstrings; describe
  arguments and return value, and give an example for anything parsing or
  formatting.
- Each namespace MAY end with a `(comment …)` rich comment block containing
  REPL calls that exercise it.
- Long namespaces are divided with section headers:

```clojure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Section title
```

### 10.5 Linting

- clj-kondo with `artifacts/.clj-kondo/config.edn`: consistent aliases,
  unsorted requires warning, redundant fn wrappers, unused public vars
  (except `^:lsp/allow-unused`), relaxed rules inside `(comment …)`.
- Every custom macro is declared in `:lint-as` or has a hook.
- clojure-lsp reads the same `cljfmt.edn` (`artifacts/.lsp/config.edn`).

---

## 11. Development workflow

| Command          | Does                                           |
|------------------|------------------------------------------------|
| `bb nrepl`       | nREPL with `:dev` (+ all `:service/*` aliases) |
| `(go)`           | Start services from `:go-services` in dev opts |
| `(reset)`        | Halt, reload changed namespaces, start again   |
| `(portal-start!)`| Start Portal on port 53755, print URL (§11.1)  |
| `(portal-stop!)` | Stop Portal, free the port                     |
| `bb fmt`         | `cljfmt fix`                                   |
| `bb fmt:check`   | `cljfmt check`                                 |
| `bb lint`        | `clj-kondo --lint src test dev`                |
| `bb test`        | Run tests                                      |
| `bb uber <name>` | Build uberjar for `service-NN-<name>`          |

- One Docker image definition parameterized by service name; the build calls
  `clojure -T:build uber :service <name>`.
- Tests mirror `src`; system-level tests start a `:test` profile system.

### 11.1 Inspecting data with Portal

[Portal](https://github.com/djblue/portal) (`djblue/portal`, in the `:dev`
alias) is the data inspector. It lives only in the `user` namespace
(`dev/user.clj`); production code never requires it.

- `(portal-start!)` starts only Portal's HTTP server on a **fixed port**
  (`PORTAL_PORT`, 53755) and adds `portal.api/submit` as a `tap>` target.
  It MUST NOT open a browser, Chrome app window, or editor panel: it prints
  the URL and port, and you visit it yourself. Because the port is fixed, the
  URL never changes; keep the tab open and reload it after a REPL restart.
- `(portal-stop!)` removes the tap target and stops the server, freeing the
  port. `(portal-clear!)` empties the UI.
- Start the server with `portal.api/start`, not `portal.api/open`: `open` also
  launches a window. Visiting `http://localhost:53755` creates a session, and
  values tapped before the tab was opened still show.
- Only one JVM can own the port. A second REPL fails with
  "Address already in use"; stop Portal in the other REPL first.
- Inspect with `tap>`, not `println`/`pprint`: resolved config
  (`(tap> (read-config))`, secrets print masked), the running system
  (`(tap> (current-system))`), query results, requests inside a handler while
  developing (`(tap> req)`). Remove `tap>` calls from `src` before committing.

```clojure
(def PORTAL_PORT
  "Fixed so the Portal URL stays stable across REPL restarts."
  53755)

(defn portal-start!
  "Starts the Portal server on `PORTAL_PORT` without opening a browser, adds it
  as a tap target and prints the URL to visit. Safe to call repeatedly."
  []
  (let [{:keys [host port]} (p/start {:port PORTAL_PORT})]
    (add-tap #'p/submit)
    (println (format "Portal running at http://%s:%d (port %d)" host port port))))

(defn portal-stop!
  "Removes the tap target and stops the Portal server, freeing the port."
  []
  (remove-tap #'p/submit)
  (p/stop)
  (println "Portal stopped"))
```

---

## 12. Scaffolding checklist for agents

When asked to create a new project or service:

1. Copy `artifacts/` dotfiles to the repo root (`cljfmt.edn`, `.gitignore`,
   `.editorconfig`, `.clj-kondo/config.edn`, `.lsp/config.edn`, `deps.edn`,
   `bb.edn`, `build/build.clj`); copy `artifacts/env.example` to `.env`.
2. Create `src/com/company/{system,config,env,request}.clj` and
   `tools/{integrant,malli,htmx,response,ring,jetty}` as described in §5, §6, §9.
3. Create `resources/system/system.edn` with one `#service` block per service,
   and `resources/system/services/<name>.edn`.
4. For each service create `service-NN-name/{main,system,server}.clj` and
   `views/layout.clj`.
5. For each entity create `<entity>.cljc` (schemas first), then
   `<entity>.db`, `<entity>.views`, `<entity>.routes`, and add
   `(<entity>.routes/routes)` to `server.clj`.
6. Copy `artifacts/dev/user.clj` (system + Portal helpers), create
   `dev/dev_opts.edn.example`, and add the service to `system-namespaces`.
7. Add a `build.clj` target for the service.
8. Run `bb fmt`, `bb lint`; start with `(go)`; open the service URL.

When asked to add a feature to an existing project:

1. Find the entity namespace; add or extend schemas before writing handlers.
2. Add `db` functions, then pure views, then handlers + route-table entries.
3. Declare cross-cutting needs as route data (`::auth/permissions`,
   `::layout/page`); write a new compiled middleware only when no existing key
   fits, and add it to the explicit middleware vector.
4. New keys: decide domain (§3.1), mechanical (§3.2), or vocabulary (§3.3).
5. New helper: find its concern (§9). If none exists, create
   `tools.<concern>`; never `utils`.
6. No new multimethods outside §8.1's allowed table; job handlers and
   rendering by type only under §8.3's conditions. Never `derive` keys we own
   (§8.5).
