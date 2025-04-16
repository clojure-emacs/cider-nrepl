(disable-warning
 {:linter :deprecations
  :symbol-matches #{#"^public boolean java\.lang\.reflect\.AccessibleObject\.isAccessible\(\)$"}
  :reason "The replacement, canAccess(Object), was added in JDK9 – but we still support JDK8."})

(disable-warning
 {:linter :deprecations
  :symbol-matches #{#"^public final void java\.lang\.Thread\.stop\(\)$"}})
