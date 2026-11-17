(ns build
  "One uberjar per service: clojure -T:build uber :service website"
  (:require
    [clojure.tools.build.api :as b]))

(def services
  "Service name → main namespace and the deps.edn aliases it needs."
  {"website"   {:main 'com.company.service-01-website.main   :aliases []}
   "dashboard" {:main 'com.company.service-02-dashboard.main :aliases []}})

(def class-dir "target/classes")

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [{:keys [service]}]
  (let [service-name (str service)
        {:keys [main aliases]} (or (get services service-name)
                                   (throw (ex-info (str "Unknown service: " service-name)
                                            {:known (keys services)})))
        basis (b/create-basis {:project "deps.edn" :aliases aliases})
        uber-file (format "target/%s.jar" service-name)]
    (clean nil)
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis :src-dirs ["src"] :class-dir class-dir :ns-compile [main]})
    (b/uber {:class-dir class-dir :uber-file uber-file :basis basis :main main})
    (println "Built" uber-file)))
