(ns cider.nrepl.middleware.format
  "Code and EDN formatting functionality."
  (:refer-clojure :exclude [read-string])
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [cljfmt.config :as fmt-config]
   [cljfmt.core :as fmt]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [nrepl.middleware.print :as print])
  (:import
   (java.io PushbackReader StringReader StringWriter)))

;;; Code formatting
(defn- keyword->symbol [kw]
  (.sym ^clojure.lang.Keyword kw))

;;; Project cljfmt configuration (.cljfmt.edn)
(defn- config-file
  "The project's cljfmt configuration file, found by walking up from the working
  directory just as cljfmt's own tooling does, or nil when there is none."
  []
  (fmt-config/find-config-file "" {:read-clj-config-files? true}))

(defn- project-config
  "The cljfmt options from the project's configuration file, or nil when none
  exists. Discovery and reading are delegated to cljfmt; the data is already in
  cljfmt's native shape, so it needs no translation."
  []
  (when-let [file (config-file)]
    (try
      ;; `.cljfmt.clj` files are read with `read-string`; disable read-eval so
      ;; formatting a file can never execute code embedded in the config.
      (binding [*read-eval* false]
        (fmt-config/read-config file))
      (catch Exception e
        (throw (ex-info (str "Failed to read cljfmt config " file ": " (.getMessage e))
                        {:file (str file)} e))))))

(defn- request-overrides
  "Translate the editor-supplied `options` into cljfmt's native shape. Indent
  rules arrive with string elements (e.g. `[[\"inner\" 0]]`) and keyword keys,
  and alias keys arrive as keywords, so both are converted."
  [options]
  (cond-> {}
    (:indents options)
    (assoc :indents (reduce-kv
                     (fn [acc kw rule]
                       (assoc acc (keyword->symbol kw)
                              (walk/postwalk #(cond-> % (string? %) keyword) rule)))
                     {}
                     (:indents options)))

    (:alias-map options)
    (assoc :alias-map (reduce-kv (fn [m k v] (assoc m (name k) v)) {} (:alias-map options)))))

(defn format-code-reply
  [{:keys [code options]}]
  (let [config (or (project-config) {})
        request (request-overrides options)
        ;; The project's config applies first; the request options layer on top.
        ;; Editor-supplied indent rules are additive, so they go through
        ;; `:extra-indents` to avoid discarding cljfmt's (or the project's) own
        ;; `:indents`.
        opts (cond-> config
               (:indents request)   (update :extra-indents merge (:indents request))
               (:alias-map request) (update :alias-map merge (:alias-map request)))]
    {:formatted-code (fmt/reformat-string code opts)}))

;;; EDN formatting
(defn- read-edn
  "Returns a vector of EDN forms, read from the string s."
  [s]
  (let [reader (PushbackReader. (StringReader. s))
        sentinel (Object.)]
    (loop [forms []]
      (let [form (edn/read {:eof sentinel
                            :default (fn [_tag value]
                                       (pr-str value))}
                           reader)]
        (if (= sentinel form)
          forms
          (recur (conj forms form)))))))

(defn- format-edn
  [edn print-fn]
  (->> (read-edn edn)
       (map (fn [value]
              (let [writer (StringWriter.)]
                (print-fn value writer)
                (str writer))))
       (str/join "\n")
       (str/trim)))

(defn format-edn-reply
  [{:keys [edn ::print/print-fn]}]
  {:formatted-edn (format-edn edn print-fn)})

;;; Middleware op handling
(defn handle-format [handler msg]
  (with-safe-transport handler msg
    "cider/format-code" format-code-reply
    "format-code" format-code-reply
    "cider/format-edn" format-edn-reply
    "format-edn" format-edn-reply))
