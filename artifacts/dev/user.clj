(ns user
  "REPL entry point, loaded automatically with the :dev alias.

  System: (go) (reset) (halt) (current-system)
  Portal: (portal-start!) (portal-clear!) (portal-stop!)"
  (:require
    [aero.core :as aero]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.tools.namespace.repl :as ns.repl]
    [com.company.config :as config]
    [com.company.system :as system]
    [integrant.core :as ig]
    [integrant.repl :as ig.repl]
    [integrant.repl.state :as ig.state]
    [portal.api :as p]))

(set! *warn-on-reflection* true)

(ns.repl/set-refresh-dirs "src" "dev" "test")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dev options

(def DEV_OPTS_FILE
  "Personal, gitignored dev preferences. Documented in dev/dev_opts.edn.example."
  "dev/dev_opts.edn")

(defn read-dev-opts []
  (let [file (io/file DEV_OPTS_FILE)]
    (when (.exists file)
      (edn/read-string (slurp file)))))

(defn read-dev-opt [k]
  (get (read-dev-opts) k))

;; Lets dev config read a dev option: #dev-opt :proxy-base-url
(defmethod aero/reader 'dev-opt
  [_ _ k]
  (read-dev-opt k))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; System

(def system-namespaces
  "Service root key → namespace holding that service's Integrant methods."
  {:service/website 'com.company.service-01-website.system
   :service/portal  'com.company.service-02-portal.system})

(defn read-config
  ([] (read-config {:profile :dev}))
  ([opts] (config/read-config (system/config-file) opts)))

(defn go
  "Starts `service-keys`. Defaults to `:go-services` in dev opts, else all."
  ([]
   (go (or (read-dev-opt :go-services) (keys system-namespaces))))
  ([service-keys]
   (run! (comp require system-namespaces) service-keys)
   (ig.repl/set-prep! #(ig/expand (read-config {:profile :dev :services service-keys})))
   (ig.repl/go service-keys)))

(defn halt []
  (ig.repl/halt))

(defn reset
  "Halts the system, reloads changed namespaces, starts again."
  []
  (ig.repl/halt)
  (ns.repl/refresh :after 'user/go))

(defn current-system []
  ig.state/system)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Portal — data inspector, https://github.com/djblue/portal
;;
;; `portal-start!` starts only Portal's HTTP server, on a fixed port, and
;; registers it as a `tap>` target. It never opens a browser or editor window:
;; visit the printed URL yourself. The URL is the same on every start, so keep
;; the tab open and reload it after restarting the REPL.
;;
;; Anything passed to `tap>` shows up in Portal, including values tapped before
;; the tab was opened. Use it instead of `println`/`pprint` for anything larger
;; than a line: system config, query results, requests, responses.
;;
;; Only one JVM can own the port. A second REPL calling `portal-start!` fails
;; with "Address already in use": stop Portal in the other REPL first.

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

(defn portal-clear!
  "Removes all values from the Portal UI."
  []
  (p/clear))

(defn portal-stop!
  "Removes the tap target and stops the Portal server, freeing the port."
  []
  (remove-tap #'p/submit)
  (p/stop)
  (println "Portal stopped"))

(comment
  ;; System
  (go)
  (go [:service/portal])
  (reset)
  (halt)
  (keys (current-system))

  ;; Portal: start, then open the printed URL (http://localhost:53755)
  (portal-start!)

  ;; Send values to Portal
  (tap> (read-config))                   ; resolved config, secrets print masked
  (tap> (current-system))                ; running Integrant system
  (tap> (read-dev-opts))
  (tap> (ns-publics 'user))

  ;; Inspect a request inside a handler while developing:
  ;; (defn show [req] (tap> req) …)
  ;; Remove such taps before committing.

  (portal-clear!)
  (portal-stop!))
