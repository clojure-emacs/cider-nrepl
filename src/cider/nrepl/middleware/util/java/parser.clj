(ns cider.nrepl.middleware.util.java.parser
  "Source and docstring info for Java classes and members"
  {:author "Jeff Valk"}
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (com.sun.javadoc ClassDoc ConstructorDoc Doc FieldDoc MethodDoc
                            Parameter Tag Type)
           (com.sun.source.tree ClassTree)
           (com.sun.tools.javac.util Context List Options)
           (com.sun.tools.javadoc DocEnv JavadocEnter JavadocTool Messager
                                  ModifierFilter RootDocImpl)
           (java.io StringReader)
           (java.net URI)
           (javax.swing.text.html HTML$Tag HTMLEditorKit$ParserCallback)
           (javax.swing.text.html.parser ParserDelegator)
           (javax.tools JavaFileObject$Kind SimpleJavaFileObject)))

;;; ## Java Source Analysis
;; Any metadata not available via reflection can be had from the source code; we
;; just need to parse it. In the case of docstrings, we actually need to parse
;; it twice -- first from Java source, and then from Javadoc HTML.

;;; ## Java Parsing
;; The Java Compiler API provides in-process access to the Javadoc compiler.
;; Unlike the standard Java compiler which it extends, the Javadoc compiler
;; preserves docstrings (obviously), as well as source position and argument
;; names in its parse tree -- pieces we're after to augment reflection info.
;; The net effect is as advertised, but the API has some rough edges:
;;
;; 1. The default `JavadocTool` entry point needlessly expects that all sources
;; will be `.java` file paths. To examine sources inside `.jar` files too, we
;; need to roll our own, which is mostly a matter of environment setup.
;;
;; 2. This setup requires manipulating a `DocEnv` instance; however prior to
;; JDK8, a required field in this class is declared with default (package) level
;; access, and no accessor methods. In JDK8, this field's visibility has been
;; [promoted][1] precisely for this sort of tool usage; however to support
;; pre-JDK8 use, we have to reflect into the field in question to set it.
;;
;; 3. By default, whenever the compiler touches an internal Java API, it emits
;; warnings that can't be suppressed. To work around this behavior, we link
;; against the symbol file `lib/ct.sym` instead of `rt.jar` by setting the
;; option `ignore.symbol.file`. [This][2] is how `javac` compiles `rt.jar`
;; itself.
;;
;; [1]: http://hg.openjdk.java.net/jdk8/tl/langtools/rev/b0909f992710
;; [2]: http://stackoverflow.com/questions/4065401/using-internal-sun-classes-with-javac

(defn set-field!
  [obj field val]
  (let [f (.getDeclaredField (class obj) field)]
    (.setAccessible f true)
    (.set f obj val)))

(defn parse-java
  "Load and parse the resource path, returning a `RootDoc` object."
  [path]
  (when-let [res (io/resource path)]
    (let [access   (ModifierFilter. ModifierFilter/ALL_ACCESS)
          context  (doto (Context.) (Messager/preRegister "cider-nrepl-javadoc"))
          options  (doto (Options/instance context) (.put "ignore.symbol.file" "y"))
          compiler (JavadocTool/make0 context)
          enter    (JavadocEnter/instance0 context)
          docenv   (doto (DocEnv/instance context)
                     (.setLocale "en")
                     (.setEncoding "utf-8")
                     (.setSilent true)
                     (set-field! "showAccess" access))
          source   (proxy [SimpleJavaFileObject]
                       [(URI. path) JavaFileObject$Kind/SOURCE]
                     (getCharContent [_] (slurp res)))
          tree     (.parse compiler source)
          classes  (->> (.defs tree)
                        (filter #(= (-> % .getKind .asInterface) ClassTree))
                        (into-array)
                        (List/from))]
      (.main enter (List/of tree))
      (RootDocImpl. docenv classes (List/nil) (List/nil)))))


;;; ## Docstring Parsing
;; Unlike source metadata (line, position, etc) that's available directly from
;; the compiler parse tree, docstrings are "some assembly required." Javadoc
;; comments use both `@tags` and HTML <tags> for semantics and formatting. The
;; latter could be passed through intact if our presentation layer could read
;; it, but we want a pure text representation, so we'll parse html to markdown.
;; This way it can either be rendered or displayed as text.

;; Use GFM extensions for multiline code blocks and tables.
(def markdown
  "Syntax map from html tag to a tuple of tag type key, start, and end chars"
  (let [chars {:p     ["\n\n"]     :code  ["`" "`"]
               :br    ["\n"]       :code* ["\n\n```\n" "```\n\n"]
               :em    ["*" "*"]    :table ["\n|--" "\n|--"]
               :str   ["**" "**"]  :thead ["" "|--\n"]
               :list  ["\n"]       :tr    ["\n" "|"]
               :li    ["- "]       :td    ["|"]
               :dd    [": "]       :th    ["|"]}
        tags  {HTML$Tag/P  :p           HTML$Tag/TT    :code
               HTML$Tag/BR :br          HTML$Tag/CODE  :code
               HTML$Tag/I  :em          HTML$Tag/VAR   :code
               HTML$Tag/EM :em          HTML$Tag/KBD   :code
               HTML$Tag/B  :str         HTML$Tag/PRE   :code*
               HTML$Tag/STRONG :str     HTML$Tag/BLOCKQUOTE :code*
               HTML$Tag/UL :list        HTML$Tag/TABLE :table
               HTML$Tag/OL :list        HTML$Tag/TR    :tr
               HTML$Tag/DL :list        HTML$Tag/TD    :td
               HTML$Tag/LI :li          HTML$Tag/TH    :th
               HTML$Tag/DT :li
               HTML$Tag/DD :dd}]
    (-> (reduce (fn [tags [tag k]]
                  (assoc tags tag (cons k (chars k))))
                {} tags)
        (with-meta chars))))

;; The HTML parser and DTD classes are in the `javax.swing` package, and have
;; internal references to the `sun.awt.AppContext` class. On Mac OS X, any use
;; of this class causes a stray GUI window to pop up. Setting the system
;; property below prevents this.
(System/setProperty "apple.awt.UIElement" "true")

;; We parse html and emit text in a single pass -- there's no need to build a
;; tree. The syntax map defines most of the output format, but a few stateful
;; rules are applied:
;;
;; 1. List items are indented to their nested depth.
;; 2. Nested elements with the same tag type key are coalesced (`<pre>` inside
;;    of `<blockquote>` is common, for instance).
;; 3. A border row is inserted between `<th>` and `<td>` table rows. Since
;;    `<thead>` and `<tbody>` are optional, we look for the th/td transition.
(defn parse-html
  "Parse html to markdown text."
  [html]
  (let [sb (StringBuilder.)
        sr (StringReader. html)
        parser (ParserDelegator.)
        stack (atom nil)
        flags (atom #{})
        handler (proxy [HTMLEditorKit$ParserCallback] []
                  (handleText [^chars chars _]
                    (.append sb (String. chars)))

                  (handleStartTag [tag _ _]
                    (let [[k start] (markdown tag)]
                      (when (and k (not= k (peek @stack)))
                        (swap! stack conj k)

                        ;; Indent list items at the current depth.
                        (when (#{:li} k)
                          (let [depth (count (filter #{:list} @stack))]
                            (.append sb "\n")
                            (dotimes [_ (dec depth)]
                              (.append sb "  "))))

                        ;; Keep th/td state; emit border between th and td rows.
                        (when (#{:th} k) (swap! flags conj :th))
                        (when (and (#{:td} k) (@flags :th))
                          (.append sb (-> markdown meta :thead last)))

                        (when start (.append sb start)))))

                  (handleEndTag [tag _]
                    (let [[k _ end] (markdown tag)]
                      (when (and k (= k (peek @stack)))
                        (swap! stack pop)
                        (when (#{:table :td} k) (swap! flags disj :th))
                        (when end (.append sb end))))))]

    (.parse parser sr handler false)
    (-> (str sb)
        (str/replace #"\n{3,}" "\n\n") ; normalize whitespace
        (str/replace #" +```" "```"))))

;; Note that @link and @linkplain are also of 'kind' @see.
(defn docstring
  "Given a Java parse tree `Doc` instance, return its parsed docstring text."
  [^Doc doc]
  (->> (.inlineTags doc)
       (map (fn [^Tag t]
              (case (.kind t)
                "@see"     (format " `%s` " (.text t)) ; TODO use .referencedClassName ...?
                "@code"    (format " `%s` " (-> t .inlineTags ^Tag first .text))
                "@literal" (format " `%s` " (-> t .inlineTags ^Tag first .text))
                (parse-html (.text t)))))
       (apply str)))


;;; ## Java Parse Tree Traversal
;; From the parse tree returned by the compiler, create a nested map structure
;; as produced by `cider.nrepl.middleware.util.java/reflect-info`: class members
;; are indexed first by name, then argument types.

(defn typesym
  "Using parse tree info, return the type's name equivalently to the `typesym`
  function in `cider.nrepl.middleware.util.java`."
  [^Type t]
  (symbol
   (str (when-let [c (.asClassDoc t)] ; when not a primitive
          (str (-> c .containingPackage .name) "."))
        (-> t .typeName (str/replace "." "$"))
        (.dimension t))))

(defprotocol Parsed
  (parse-info [o]))

(extend-protocol Parsed
  ConstructorDoc
  (parse-info [c]
    {:name (-> c .qualifiedName symbol)
     :argtypes (mapv #(-> ^Parameter % .type typesym) (.parameters c))
     :argnames (mapv #(-> ^Parameter % .name symbol) (.parameters c))})

  MethodDoc
  (parse-info [m]
    {:argtypes (mapv #(-> ^Parameter % .type typesym) (.parameters m))
     :argnames (mapv #(-> ^Parameter % .name symbol) (.parameters m))})

  FieldDoc
  (parse-info [f])

  ClassDoc
  (parse-info [c]
    {:class   (typesym c)
     :doc     (docstring c)
     :line    (-> c .position .line)
     :column  (-> c .position .column)
     :members (->> (concat (.constructors c) (.methods c) (.fields c))
                   ;; Merge type-specific attributes with common ones.
                   (map (fn [^Doc m]
                          (merge {:name   (-> m .name symbol)
                                  :line   (-> m .position .line)
                                  :column (-> m .position .column)
                                  :doc    (docstring m)}
                                 (parse-info m))))
                   ;; Index by name, argtypes. Args for fields are nil.
                   (group-by :name)
                   (reduce (fn [ret [n ms]]
                             (assoc ret n (zipmap (map :argtypes ms) ms)))
                           {}))}))

(defn source-path
  "Return the relative `.java` source path for the top-level class."
  [class]
  (-> (str/replace (str class) #"\$.*" "")
      (str/replace "." "/")
      (str ".java")))

(defn source-info
  "If the source for the Java class is available on the classparh, parse it
  and return info to supplement reflection. Specifically this includes source
  file and position, docstring, and argument name info. Info returned has the
  same structure as that of `cider.nrepl.middleware.util.java/reflect-info`."
  [class]
  {:pre [(symbol? class)]}
  (let [path (source-path class)]
    (when-let [root (parse-java path)]
      (assoc (->> (map parse-info (.classes root))
                  (filter #(= class (:class %)))
                  (first))
        :file path))))
