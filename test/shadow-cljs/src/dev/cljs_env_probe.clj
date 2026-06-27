;;; Diagnostic nREPL middleware for cider-nrepl's shadow-cljs support.
;;;
;;; The make-or-break question for native shadow-cljs support (no piggieback)
;;; is: when a session is in a shadow cljs REPL, is the build-id (and therefore
;;; the compiler env) reachable from the *session map* for ordinary, non-eval
;;; messages - i.e. the exact context cider-nrepl's info/complete/eldoc ops run
;;; in?
;;;
;;; This middleware, for every message carrying a session, reports what it can
;;; find and prints it to stderr. It's already wired into shadow-cljs.edn's
;;; :nrepl middleware, so:
;;;   1. Start the server:        npx shadow-cljs server   (or `watch :app`)
;;;   2. Connect & start a REPL:  via your editor, or `node-repl` / `cljs-repl`.
;;;   3. Evaluate a couple of forms, then idle (let the editor fire its own
;;;      eldoc/complete ops, or send some yourself).
;;;   4. Read the shadow server's stderr for the "cljs-env-probe" lines.
;;;
;;; WHAT TO LOOK FOR:
;;;   On messages OTHER than the eval that started the REPL - ideally an
;;;   editor-driven op like "eldoc"/"complete":
;;;       :repl-state-in-session?       true
;;;       :build-id                     <your-build-keyword>
;;;       :compiler-env-found?          true
;;;       :compiler-env-has-namespaces? true
;;;   => native shadow support is straightforward; the provider chain works.
;;;   If those are only true transiently (only on the eval that set them),
;;;   shadow tracks the build per-session some other way - the
;;;   :cljs-related-session-vars dump shows what it parks in the session.

(ns cljs-env-probe
  (:require
   [clojure.pprint :as pp]
   [nrepl.middleware :refer [set-descriptor!]]))

(defn- maybe-deref [x] (if (instance? clojure.lang.IDeref x) @x x))

(defn- safe [f] (try (f) (catch Throwable _ ::error)))

(defn- probe-session [session]
  (let [m              (maybe-deref session)
        var-keys       (filter var? (keys m))
        ;; any session-stored dynamic vars that look cljs-related
        cljs-vars      (->> var-keys
                            (filter #(re-find #"shadow|piggieback|cljs"
                                              (str (.ns ^clojure.lang.Var %))))
                            (mapv #(symbol (str (.ns ^clojure.lang.Var %))
                                           (str (.sym ^clojure.lang.Var %)))))
        repl-state-var (safe #(requiring-resolve
                               'shadow.cljs.devtools.server.nrepl-impl/*repl-state*))
        repl-state     (when (var? repl-state-var)
                         (maybe-deref (get m repl-state-var)))
        build-id       (when (map? repl-state) (:build-id repl-state))
        compiler-env   (safe #(requiring-resolve 'shadow.cljs.devtools.api/compiler-env))
        env            (when (and build-id (ifn? compiler-env))
                         (safe #(compiler-env build-id)))
        ;; does the (vendored or real) piggieback var already hold the env?
        pb-var         (safe #(requiring-resolve 'cider.piggieback/*cljs-compiler-env*))
        pb-env         (when (var? pb-var) (maybe-deref (get m pb-var)))]
    {:cljs-related-session-vars cljs-vars
     :repl-state-in-session?    (and (var? repl-state-var) (contains? m repl-state-var))
     :repl-state-keys           (when (map? repl-state) (keys repl-state))
     :build-id                  build-id
     :compiler-env-found?       (boolean (and env (not= env ::error)))
     :compiler-env-has-namespaces?
     (boolean (some-> env maybe-deref :cljs.analyzer/namespaces seq))
     :piggieback-var-in-session? (and (var? pb-var) (contains? m pb-var))
     :piggieback-env-has-namespaces?
     (boolean (some-> pb-env maybe-deref :cljs.analyzer/namespaces seq))}))

;; In an nREPL handler `*out*`/`*err*` are bound to the session's streams (sent
;; back to the client), so they're an unreliable place to land diagnostics on
;; the server side. Append to a file instead - tail it while you poke the REPL:
;;   tail -f /tmp/cljs-env-probe.log
(def ^:private log-file "/tmp/cljs-env-probe.log")

(defn wrap-cljs-env-probe [handler]
  (fn [{:keys [op session] :as msg}]
    (when session
      (let [info (safe #(probe-session session))]
        ;; only record when something cljs-ish is in the session, to stay quiet
        ;; on plain Clojure traffic
        (when (or (seq (:cljs-related-session-vars info))
                  (:build-id info))
          (spit log-file
                (str "=== cljs-env-probe [op: " op "] ===\n"
                     (with-out-str (pp/pprint info)))
                :append true))))
    (handler msg)))

(set-descriptor! #'wrap-cljs-env-probe
                 {:requires #{} :expects #{} :handles {}})

;; load-time marker, so we can confirm shadow actually required this ns when it
;; built the nREPL middleware stack
(spit log-file "=== cljs-env-probe loaded ===\n" :append true)
