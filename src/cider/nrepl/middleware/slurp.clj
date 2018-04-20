(ns cider.nrepl.middleware.slurp
  "Rich reading & handling for CIDER.
  
  Goes with middleware.content-types, providing the capability to
  convert URLs to values which can be handled nicely."
  {:authors ["Reid 'arrdem' McKenzie <me@arrdem.com>"]}
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net MalformedURLException URL]
           [java.nio.file Files Path Paths]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro if-class
  "Conditional compilation macro for when a given class is available.

  If the given class can be resolved, expands to `then-expr`,
  otherwise expands to `else-expr`. `else-expr` defaults to `nil`."
  ([classname then-expr]
   `(if-class ~classname ~then-expr nil))
  ([classname then-expr else-expr]
   (if (try (eval `(import ~classname)) true
            (catch ClassNotFoundException e false))
     then-expr else-expr)))

(def known-content-types
  (->> (io/resource "content-types.edn")
       (io/reader)
       (java.io.PushbackReader.)
       (edn/read)
       (mapcat (fn [[content-type exts]]
                 (for [ext exts]
                   [ext content-type])))
       (into {})))

(defn- split-last
  [^String to-split ^String where]
  (let [idx (.lastIndexOf to-split where)]
    (if (not= idx -1)
      (.substring to-split (+ (count where) idx) (count to-split))
      to-split)))

(def content-type-pattern
  #"(?<realType>[^;]+)(;(?<parameters>.*?))?$")

(defn normalize-content-type
  "nREPL's content-type headers are structured as a pair
  `[type {:as attrs}]`. This method normalizes RFC
  compliant content-types to this form."
  [^String content-type]
  (if-let [match (re-find content-type-pattern content-type)]
    (let [[_ type _ parameters] match]
      [type
       (into {}
             (when parameters
               (map #(str/split % #"=")
                    (str/split parameters #";"))))])
    [content-type {}]))

(defn get-file-content-type [^Path p]
  (or (get known-content-types (split-last (.toString p) "."))
      (Files/probeContentType p)))

;; FIXME (arrdem 2018-04-11):
;;   Remove this if-class when we have jdk1.8 min
(defn base64-string
  [buff]
  (if-class java.util.Base64
    (.encode (Base64/getEncoder) ^bytes buff)))

(defn slurp-reply [content-type buff]
  (let [^String real-type (first content-type)
        text? (.contains real-type "text")]
    (cond-> {:content-type content-type}
      (not text?)
      (assoc :content-transfer-encoding "base64"
             :body (base64-string buff))

      ;; FIXME (arrdem 2018-04-03): This is not generally safe, but if
      ;;   you're on a non-utf-8 platform you already know that.
      text?
      (assoc :body (String. buff "utf-8")
             :content-transfer-encoding "utf-8"))))

(defn slurp-url-to-content+body
  "Attempts to parse and then to slurp a URL, producing a content-typed response."
  [url-str]
  (if-let [url (try (URL. url-str)
                    (catch MalformedURLException e nil))]
    (if (= (.getProtocol url) "file") ;; expected common case
      (let [^Path p (Paths/get (.toURI url))
            content-type (normalize-content-type
                          (get-file-content-type p))
            buff (Files/readAllBytes p)]
        (slurp-reply content-type buff))

      ;; It's not a file, so just try to open it on up
      (with-open [conn (.openConnection url)]
        (let [content-type (normalize-content-type
                            (.getContentType conn))
              ;; FIXME (arrdem 2018-04-03):
              ;;   There's gotta be a better way here
              buff (.readAllBytes (.getInputStream conn))]
          (slurp-reply content-type buff))))))

;; FIXME (arrdem 2018-04-11):
;;   Remove this if-class when we have jdk1.8 min
(defn handle-slurp
  "Message handler which just responds to slurp ops.

  If the slurp is malformed, or fails, lets the rest of the stack keep going."
  [handler msg]
  (let [{:keys [op url transport]} msg]
    (handler (if (and (= "slurp" op) url)
               (if-class java.util.Base64
                 (slurp-url-to-content+body url)
                 {:error "`java.util.Base64` cannot be found, `slurp` op is disabled."})
               msg))))
