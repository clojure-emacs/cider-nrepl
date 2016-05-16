(ns cider.tasks
  (:require [boot.core :refer [deftask]]
            [boot.repl :as repl]
            [boot.util :as util]))

(deftask add-middleware
  "CIDER middleware injection task

  This task allows to inject middleware in `boot.repl/*default-middleware*`.
  Just pass it as -m|-middleware. The input is a name but will be converted to
  symbol."
  [m middleware MIDDLEWARE #{sym} "Name of the middleware to inject"]
  (if-let [default-middleware (resolve 'boot.repl/*default-middleware*)]
    (do (util/dbug* "Boot's default middleware: %s\n" (vec @@default-middleware))
        (swap! @default-middleware concat middleware)
        (util/dbug* "After cider-nrepl injection: %s\n" (vec @@default-middleware)))
    (util/dbug "Cannot resolve boot.repl/*default-middleware*, skipping middleware injection...\n"))
  identity)
