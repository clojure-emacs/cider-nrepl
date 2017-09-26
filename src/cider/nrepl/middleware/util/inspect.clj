(ns cider.nrepl.middleware.util.inspect
  "Adapted from swank-clojure and javert."
  (:require [clojure.string :as s])
  (:import (java.lang.reflect Field)
           clojure.lang.Seqable))

;;
;; Navigating Inspector State
;;

(declare inspect-render inspect-value)

(defn- reset-index [inspector]
  (merge inspector {:counter 0 :index []}))

(defn push-item-to-path
  "Takes the current inspector index, the `idx` of the value in it to be navigated
  to, and the path so far, and returns the updated path to the selected value."
  [index idx path]
  (if (>= idx (count index))
    (conj path '<unknown>)
    (if (= idx 0)
      (conj path 'class)
      (let [klass (first index)]
        (cond
          ;; If value's class is a map, jumping into its value means finding a
          ;; MapEntry object for the key.
          ((supers klass) clojure.lang.IPersistentMap)
          (conj path (list 'find (first (nth index idx))))

          ;; For a MapEntry, clicking on the first item means getting the key of
          ;; the MapEntry, second - means getting the value by the key.
          (= klass clojure.lang.MapEntry)
          (if (= idx 1)
            (conj path 'first)
            (let [[_ key] (peek path)]
              (conj (pop path)
                    (if (keyword? key)
                      key
                      (list 'get key)))))

          ;; For sequential things going down means getting the nth value.
          ((supers klass) clojure.lang.Sequential)
          (conj path (list 'nth (dec idx)))

          :else (conj path '<unknown>))))))

(defn pop-item-from-path
  "Takes the current inspector path, and returns an updated path one level up."
  [path]
  (let [last-node (peek path)]
    (if (or (keyword? last-node)
            (and (list? last-node) (= (first last-node) 'get)))
      (let [key (if (keyword? last-node)
                  last-node
                  (second last-node))]
        (conj (pop path) (list 'find key)))
      (pop path))))

(defn clear
  "Clear an inspector's state."
  [inspector]
  (merge (reset-index inspector)
         {:value nil :stack [] :path [] :rendered '() :current-page 0}))

(defn fresh
  "Return an empty inspector."
  []
  (inspect-render (clear {:page-size 32})))

(defn start
  "Put a new value onto the inspector stack."
  [inspector value]
  (-> (clear inspector)
      (inspect-render value)))

(defn up
  "Pop the stack and re-render an earlier value."
  [inspector]
  (let [stack (:stack inspector)]
    (if (empty? stack)
      (inspect-render inspector)
      (-> inspector
          (update-in [:path] pop-item-from-path)
          (inspect-render (last stack))
          (update-in [:stack] pop)))))

(defn down
  "Drill down to an indexed object referred to by the previously
   rendered value."
  [inspector idx]
  {:pre [(integer? idx)]}
  (let [{:keys [index path]} inspector
        new (get index idx)
        val (:value inspector)
        new-path (push-item-to-path index idx path)]
    (-> (update-in inspector [:stack] conj val)
        (assoc :path new-path)
        (inspect-render new))))

(defn next-page
  "Jump to the next page when inspecting a paginated sequence/map. Does nothing
  if already on the last page."
  [inspector]
  (inspect-render (update-in inspector [:current-page] inc)))

(defn prev-page
  "Jump to the previous page when inspecting a paginated sequence/map. Does
  nothing if already on the first page."
  [inspector]
  (inspect-render (update-in inspector [:current-page] dec)))

(defn set-page-size
  "Set the page size in pagination mode to the specified value. Current page
  will be reset to zero."
  [inspector new-page-size]
  {:pre [(integer? new-page-size) (pos? new-page-size)]}
  (inspect-render (assoc inspector
                         :page-size new-page-size
                         :current-page 0)))

(declare inspector-value-string)

;;
;; Render values onto the inspector's current state
;;
;; Good for method extenders to use

(defn- atom? [val]
  (some #(% val) [number? string? symbol? keyword?]))

(defn safe-pr-seq [value fmt]
  (->> (map inspect-value value)
       (interpose " ")
       s/join
       (format fmt)))

(defn value-types [value]
  (cond
    (atom? value) :atom
    (and (instance? Seqable value) (empty? value)) :seq-empty
    (and (map? value) (< (count value) 5))         :map
    (map? value)                                   :map-long
    (and (vector? value) (< (count value) 5))      :vector
    (vector? value)                                :vector-long
    (and (seq? value) (not (counted? value)))      :lazy-seq
    (and (seq? value) (< (count value) 5))         :list
    (seq? value)                                   :list-long
    (and (set? value) (< (count value) 5))         :set
    (set? value)                                   :set-long
    :else (or (:inspector-tag (meta value))
              (type value))))

(defmulti inspect-value #'value-types)

(defmethod inspect-value :atom [value]
  (pr-str value))

(defmethod inspect-value :seq-empty [value]
  (pr-str value))

(defmethod inspect-value :map [value]
  (->> value
       (map (fn [[k v]]
              (str (inspect-value k) " " (inspect-value v))))
       (interpose ", ")
       s/join
       (format "{ %s }")))

(defmethod inspect-value :map-long [value]
  (str "{ " (ffirst value) " "
       (inspect-value (second (first value))) ", ... }"))

(defmethod inspect-value :vector [value]
  (safe-pr-seq value "[ %s ]"))

(defmethod inspect-value :vector-long [value]
  (safe-pr-seq (take 5 value) "[ %s ... ]"))

(defmethod inspect-value :lazy-seq [value]
  (let [first-six (take 6 value)]
    (if (= (count first-six) 6)
      (safe-pr-seq (take 5 value) "( %s ... )")
      (safe-pr-seq first-six "( %s )"))))

(defmethod inspect-value :list [value]
  (safe-pr-seq value "( %s )"))

(defmethod inspect-value :list-long [value]
  (safe-pr-seq (take 5 value) "( %s ... )"))

(defmethod inspect-value :set [value]
  (safe-pr-seq value "#{ %s }"))

(defmethod inspect-value :set-long [value]
  (safe-pr-seq (take 5 value) "#{ %s ... }"))

(defmethod inspect-value java.lang.Class [value]
  (pr-str value))

(defmethod inspect-value :default [value]
  (str value))

(defn render-onto [inspector coll]
  (update-in inspector [:rendered] concat coll))

(defn render [inspector & values]
  (render-onto inspector values))

(defn render-ln [inspector & values]
  (render-onto inspector (concat values '((:newline)))))

(defn render-value [inspector value]
  (let [{:keys [counter]} inspector
        expr `(:value ~(inspect-value value) ~counter)]
    (-> inspector
        (update-in [:index] conj value)
        (update-in [:counter] inc)
        (update-in [:rendered] concat (list expr)))))

(defn render-labeled-value [inspector label value]
  (-> inspector
      (render label ": ")
      (render-value value)
      (render-ln)))

(defn render-indexed-values [inspector obj]
  (let [{:keys [current-page page-size]} inspector
        last-page (if (or (instance? clojure.lang.Counted obj)
                          ;; if there are no more items after the current page,
                          ;; we must have reached the end of the collection, so
                          ;; it's not infinite.
                          (empty? (drop (* (inc current-page) page-size) obj)))
                    (quot (dec (count obj)) page-size)
                    Integer/MAX_VALUE) ;; possibly infinite
        ;; current-page might contain an incorrect value, fix that
        current-page (cond (< current-page 0) 0
                           (> current-page last-page) last-page
                           :else current-page)
        chunk-to-display (->> (map-indexed list obj)
                              (drop (* current-page page-size))
                              (take page-size))
        paginate? (not= last-page 0)]
    (as-> inspector ins
      (if (> current-page 0)
        (-> ins
            (render "  ...")
            (render '(:newline)))
        ins)

      (reduce (fn [ins [idx val]]
                (-> ins
                    (render "  " (str idx) ". ")
                    (render-value val)
                    (render '(:newline))))
              ins
              chunk-to-display)

      (if (< current-page last-page)
        (render ins "  ...")
        ins)

      (if paginate?
        (-> ins
            (render '(:newline))
            (render (format "  Page size: %d, showing page: %d of %s"
                            page-size (inc current-page)
                            (if (= last-page Integer/MAX_VALUE)
                              "?" (inc last-page))))
            (assoc :current-page current-page))
        ins))))

(defn render-map-values [inspector mappable]
  (reduce (fn [ins [key val]]
            (-> ins
                (render "  ")
                (render-value key)
                (render " = ")
                (render-value val)
                (render '(:newline))))
          inspector
          mappable))

(defn render-meta-information [inspector obj]
  (if (seq (meta obj))
    (-> inspector
        (render-ln "Meta Information: ")
        (render-map-values (meta obj)))
    inspector))

;; Inspector multimethod
(defn known-types [ins obj]
  (cond
    (nil? obj) :nil
    (map? obj) :seq
    (vector? obj) :seq
    (seq? obj) :seq
    (set? obj) :seq
    (var? obj) :var
    (string? obj) :string
    (instance? Class obj) :class
    (instance? clojure.lang.Namespace obj) :namespace
    (instance? clojure.lang.ARef obj) :aref
    (.isArray (class obj)) :array
    :default (or (:inspector-tag (meta obj))
                 (type obj))))

(defmulti inspect #'known-types)

(defmethod inspect :nil [inspector obj]
  (-> inspector
      (render-ln "nil")))

(defmethod inspect :seq [inspector obj]
  (-> inspector
      (render-labeled-value "Class" (class obj))
      (render-meta-information obj)
      (render-ln "Contents: ")
      (render-indexed-values obj)))

(defmethod inspect :array [inspector obj]
  (-> inspector
      (render-labeled-value "Class" (class obj))
      (render-labeled-value "Count" (alength obj))
      (render-labeled-value "Component Type" (.getComponentType (class obj)))
      (render-ln "Contents: ")
      (render-indexed-values obj)))

(defmethod inspect :var [inspector ^clojure.lang.Var obj]
  (let [header-added
        (-> inspector
            (render-labeled-value "Class" (class obj))
            (render-meta-information obj))]
    (if (.isBound obj)
      (-> header-added
          (render "Value: ")
          (render-value (var-get obj)))
      header-added)))

(defmethod inspect :string [inspector ^java.lang.String obj]
  (-> inspector
      (render-labeled-value "Class" (class obj))
      (render "Value: " (pr-str obj))))

(defmethod inspect :default [inspector obj]
  (let [^"[Ljava.lang.reflect.Field;" fields (.getDeclaredFields (class obj))
        names (map #(.getName ^Field %) fields)
        get (fn [^Field f]
              (try (.setAccessible f true)
                   (catch java.lang.SecurityException e))
              (try (.get f obj)
                   (catch java.lang.IllegalAccessException e
                     "Access denied.")))
        vals (map get fields)]
    (-> inspector
        (render-labeled-value "Type" (class obj))
        (render-labeled-value "Value" (pr-str obj))
        (render-ln "---")
        (render-ln "Fields: ")
        (render-map-values (zipmap names vals)))))

(defn- render-class-section [inspector obj section]
  (let [method (symbol (str ".get" (name section)))
        elements (eval (list method obj))]
    (if (seq elements)
      `(~(name section) ": " (:newline)
                        ~@(mapcat (fn [f]
                                    `("  " (:value ~f) (:newline))) elements)))))

(defn- render-section [obj inspector section]
  (let [method (symbol (str ".get" (name section)))
        elements (eval (list method obj))]
    (if-not elements
      inspector
      (reduce (fn [ins elt]
                (-> ins
                    (render "  ")
                    (render-value elt)
                    (render-ln)))
              (-> inspector
                  (render-ln)
                  (render-ln "--- " (name section) ": "))
              elements))))

(defmethod inspect :class [inspector ^Class obj]
  (reduce (partial render-section obj)
          (render-labeled-value inspector "Type" (class obj))
          [:Interfaces :Constructors :Fields :Methods]))

(defmethod inspect :aref [inspector ^clojure.lang.ARef obj]
  (-> inspector
      (render-labeled-value "Type" (class obj))
      (render-ln "Contains:")
      (render-ln)
      (inspect (deref obj))))

(defn ns-refers-by-ns [^clojure.lang.Namespace ns]
  (group-by (fn [^clojure.lang.Var v] (.ns v))
            (map val (ns-refers ns))))

(defmethod inspect :namespace [inspector ^clojure.lang.Namespace obj]
  (-> inspector
      (render-labeled-value "Class" (class obj))
      (render-labeled-value "Count" (count (ns-map obj)))
      (render-ln "---")
      (render-ln "Refer from: ")
      (render-map-values (ns-refers-by-ns obj))
      (render-labeled-value "Imports" (ns-imports obj))
      (render-labeled-value "Interns" (ns-interns obj))))

;;
;; Entry point to inspect a value and get the serialized rep
;;
(defn render-reference [inspector]
  (let [{:keys [type ns sym expr]} (:reference inspector)]
    (cond (= type :var)
          (render-ln inspector "Var: #'" ns "/" sym)
          (= type :expr)
          (render-ln inspector "Expr: " expr)
          :default
          inspector)))

(defn render-path [inspector]
  (let [path (:path inspector)]
    (if (and (seq path) (not-any? #(= % '<unknown>) path))
      (-> inspector
          (render '(:newline))
          (render (str "  Path: "
                       (s/join " " (:path inspector)))))
      inspector)))

(defn inspect-render
  ([inspector] (inspect-render inspector (:value inspector)))
  ([inspector value] (-> (reset-index inspector)
                         (assoc :rendered [])
                         (assoc :value value)
                         (render-reference)
                         (inspect value)
                         (render-path))))

;; Get a human readable printout of rendered sequence
(defmulti inspect-print-component first)

(defmethod inspect-print-component :newline [_]
  (prn))

(defmethod inspect-print-component :value [[_ & xs]]
  (print (str (first xs))))

(defmethod inspect-print-component :default [x]
  (print x))

(defn inspect-print [x]
  (doseq [component (:rendered (inspect-render (fresh) x))]
    (inspect-print-component component)))
