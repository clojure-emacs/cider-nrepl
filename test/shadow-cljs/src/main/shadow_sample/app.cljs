(ns shadow-sample.app)

(defn init []
  (js/console.log "Hello from shadow-sample.app"))

(defn ^:dev/after-load reload []
  (js/console.log "reloaded"))
