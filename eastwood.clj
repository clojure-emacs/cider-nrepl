(disable-warning
 {:linter :unused-ret-vals
  :if-inside-macroexpansion-of #{'boot.core/deftask}
  :within-depth 7
  :reason "The `deftask` macro often includes calls to `boot.util/dbug*` whose purpose is solely for side-effects."})

(disable-warning
 {:linter :deprecations
  :symbol-matches #{#"^public boolean java\.lang\.reflect\.AccessibleObject\.isAccessible\(\)$"}
  :reason "The replacement, canAccess(Object), was added in JDK9 – but we still support JDK8."})

(disable-warning
 {:linter :deprecations
  :symbol-matches #{#"^public final void java\.lang\.Thread\.stop\(\)$"}})

(disable-warning
 {:linter :deprecations
  :symbol-matches #{#"^public long java\.lang\.Thread\.getId\(\)$"}})

(disable-warning
 {:linter :deprecations
  :symbol-matches #{#"^public int java\.util\.logging\.LogRecord\.getThreadID\(\)$"}})
