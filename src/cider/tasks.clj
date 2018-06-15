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
    (do (util/dbug* "Current middleware: %s\n" (vec @@default-middleware))
        (swap! @default-middleware concat middleware)
        (util/dbug* "After cider-nrepl injection: %s\n" (vec @@default-middleware)))
    (util/dbug "Cannot resolve boot.repl/*default-middleware*, skipping middleware injection...\n"))
  identity)

(deftask nrepl-server
  "Start a nREPL server.

  Optionally accepts port and host.

  Note that the boot.repl/*default-middleware* atom is read for the list of the
  middleware symbols."
  [b bind ADDR      str   "The address server listens on."
   p port PORT      int   "The port to listen on and/or connect to."]
  (let [default-mws @@(resolve 'boot.repl/*default-middleware*)]
    (util/dbug* "nREPL middleware: %s\n" (vec default-mws))
    (boot.core/with-pass-thru [_]
      (require 'cider-nrepl.main)
      ((resolve 'cider-nrepl.main/init) {:middleware default-mws
                                         :port port
                                         :bind bind}))))
