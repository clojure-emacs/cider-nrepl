(disable-warning
 {:linter :unused-ret-vals
  :if-inside-macroexpansion-of #{'boot.core/deftask}
  :within-depth 7
  :reason "The `deftask` macro often includes calls to `boot.util/dbug*` whose purpose is solely for side-effects."})
