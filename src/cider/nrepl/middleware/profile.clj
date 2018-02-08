(ns cider.nrepl.middleware.profile
  "This profiler is intended for interactive profiling applications where you do
  not expect a profiling tool to automatically compensate for JVM
  warm-up and garbage collection issues. If you are doing numeric
  computing or writing other purely functional code that can be
  executed repeatedly without unpleasant side effects, I recommend you
  at the very least check out Criterium.

  If you are primarily concerned about the influence of JVM-exogenous
  factors on your code—HTTP requests, SQL queries, other network-
  or (possibly) filesystem-accessing operations—then this package may
  be just what the doctor ordered.

  Based on older middleware (nrepl-profile) that's not actively
  maintained anymore."
  {:author "Edwin Watkeys"}
  (:require [clojure.string :as s]
            [profile.core :as p]
            [clojure.tools.nrepl.transport :as t]
            [clojure.tools.nrepl.misc :refer [response-for]]))

(defn send-exception
  [e msg transport]
  (t/send transport (response-for msg :status :done :value "exception")))

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
    (catch Exception e (send-exception e msg transport))))

(defn profile-var-summary
  [{:keys [ns sym transport] :as msg}]
  (try
    (if-let [v (ns-resolve (symbol ns) (symbol sym))]
      (if-let [table (with-out-str (binding [*err* *out*]
                                     (p/print-entry-summary v)))]
        (t/send transport
                (response-for msg
                              :status :done
                              :err table))
        (t/send transport
                (response-for msg
                              :status :done
                              :err (format "No profile data for %s." v))))
      (t/send transport
              (response-for msg
                            :status :done
                            :value (format "Var %s/%s is not bound." ns sym))))
    (catch Exception e (prn :e e) (send-exception e msg transport))))

(defn profile-summary
  [{:keys [transport] :as msg}]
  (try
    (t/send transport
            (response-for msg
                          :status :done
                          :err (with-out-str
                                 (binding [*err* *out*] (p/print-summary)))))
    (catch Exception e (send-exception e msg transport))))

(defn clear-profile
  [{:keys [transport] :as msg}]
  (try
    (p/clear-profile-data)
    (t/send transport
            (response-for msg
                          :status :done
                          :value "cleared"))
    (catch Exception e (send-exception e msg transport))))

(defn toggle-profile-ns
  [{:keys [ns transport] :as msg}]
  (try (let [profiled? (p/toggle-profile-ns (symbol ns))]
         (t/send transport
                 (response-for
                  msg
                  :status :done
                  :value (if profiled? "profiled" "unprofiled"))))
       (catch Exception e (send-exception e msg transport))))

(defn is-var-profiled
  [{:keys [ns sym transport] :as msg}]
  (try (let [var (ns-resolve (symbol ns) (symbol sym))
             profiled? (p/profiled? @var)]
         (t/send transport
                 (response-for
                  msg
                  :status :done
                  :value (if profiled? "profiled" "unprofiled"))))
       (catch Exception e (send-exception e msg transport))))

(defn get-max-samples
  [{:keys [transport] :as msg}]
  (try (t/send transport
               (response-for
                msg
                :status :done
                :value (str (p/max-sample-count))))
       (catch Exception e (send-exception e msg transport))))

(defn normalize-max-samples [n]
  (cond (and (sequential? n) (empty? n)) nil
        (string? n) (Long/parseLong n)
        :else n))

(defn set-max-samples
  [{:keys [max-samples transport] :as msg}]
  (try (let [max-samples (normalize-max-samples max-samples)]
         (p/set-max-sample-count max-samples)
         (t/send transport
                 (response-for
                  msg
                  :status :done
                  :value (str (p/max-sample-count)))))
       (catch Exception e  (send-exception e msg transport))))

(defn handle-profile
  [handler msg]
  (let [{:keys [op] } msg]
    (case op
      "toggle-profile"      (toggle-profile msg)
      "toggle-profile-ns"   (toggle-profile-ns msg)
      "is-var-profiled"     (is-var-profiled msg)
      "profile-summary"     (profile-summary msg)
      "profile-var-summary" (profile-var-summary msg)
      "clear-profile"       (clear-profile msg)
      "get-max-samples"     (get-max-samples msg)
      "set-max-samples"     (set-max-samples msg)
      (handler msg))))
