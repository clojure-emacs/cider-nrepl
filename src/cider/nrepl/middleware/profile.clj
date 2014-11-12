(ns cider.nrepl.middleware.profile
  (:require [clojure.string :as s]
            [profile.core :as p]
            [clojure.tools.nrepl.transport :as t]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [cider.nrepl.middleware.util.misc :as u]))

(defn toggle-profile
  [{:keys [ns sym transport] :as msg}]
  (try
    (if-let [v (ns-resolve (symbol ns) (symbol sym))]
      (let [profiled? (p/toggle-profile-var* v)]
        (t/send transport
                (response-for
                 msg
                 :status :done
                 :value (if profiled? "profiled" "unprofiled"))))
      (t/send transport
              (response-for
               msg
               :status #{:toggle-profile-not-such-var :done}
               :value "unbound")))
    (catch Exception e
      (t/send transport
              (response-for msg (u/err-info e :toggle-profile-error))))))

(defn profile-summary
  [{:keys [transport] :as msg}]
  (try
    (t/send transport
            (response-for msg
                          :status :done
                          :err (with-out-str
                                 (binding [*err* *out*] (p/print-summary)))))
    (catch Exception e
      (t/send transport
              (response-for msg (u/err-info e :profile-summary-error))))))

(defn clear-profile
  [{:keys [transport] :as msg}]
  (try
    (p/clear-profile-data)
    (t/send transport
            (response-for msg
                          :status :done
                          :value "cleared"))
    (catch Exception e
      (t/send transport
              (response-for msg (u/err-info e :clear-profile-error))))))

(defn wrap-profile
  "Middleware that toggles profiling of a given var."
  [handler]
  (fn [{:keys [op] :as msg}]
    (case op
      "toggle-profile"
      (toggle-profile msg)
      "profile-summary"
      (profile-summary msg)
      "clear-profile"
      (clear-profile msg)
      (handler msg))))

(set-descriptor!
 #'wrap-profile
 {:handles
  {"toggle-profile"
   {:doc "Toggle profiling of a given var."
    :requires {"sym" "The symbol to profile"
               "ns" "The current namespace"}
    :returns {"status" "Done"
              "value" "'profiled' if profiling enabled, 'unprofiled' if disabled, 'unbound' if ns/sym not bound"}}
   "profile-summary"
   {:doc "Return profiling data summary."
    :requires {}
    :returns {"status" "Done"
              "err" "Content of profile summary report"}}
   "clear-profile"
   {:doc "Clears profile of samples."
    :requires {}
    :returns {"status" "Done"}}}})
