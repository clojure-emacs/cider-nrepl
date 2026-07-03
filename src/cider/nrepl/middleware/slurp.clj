(ns cider.nrepl.middleware.slurp
  "Rich reading & handling for CIDER.

  Goes with middleware.content-types, providing the capability to
  convert URLs to values which can be handled nicely."
  {:authors ["Reid 'arrdem' McKenzie <me@arrdem.com>"]}
  (:require
   [cider.nrepl.middleware.util :refer [respond-to]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io ByteArrayOutputStream InputStream)
   (java.net MalformedURLException URI URL URLConnection)
   (java.nio.file Files Path Paths)
   (java.util Base64)))

(def ^:dynamic *max-content-size*
  "Maximum number of bytes `cider/slurp` is willing to read.
  Larger resources get a size-only placeholder reply instead of their
  content, protecting both the JVM and the client from unbounded reads."
  (* 4 1024 1024))

(def connection-timeout-ms
  "Connect/read timeout for slurping non-file URLs."
  10000)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def known-content-types
  (->> (io/resource "cider/nrepl/content-types.edn")
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
      (Files/probeContentType p)
      "application/octet-stream"))

(defn base64-bytes
  [^bytes buff]
  (.encodeToString (Base64/getEncoder) buff))

(defn slurp-reply [location content-type ^bytes buff]
  (let [^String real-type (first content-type)
        binary? (= "application/octet-stream" real-type)
        text? (.contains real-type "text")]
    (cond
      binary?
      {:content-type content-type
       :body (str "#binary[location=" location ",size=" (count buff) "]")}

      text?
      {:content-type content-type
       :body (String. buff "utf-8")}

      :else
      {:content-type content-type
       :content-transfer-encoding "base64"
       :body (base64-bytes buff)})))

(defn- too-large-reply
  "A size-only placeholder reply for content exceeding `*max-content-size*`."
  [location size]
  {:content-type (normalize-content-type "application/octet-stream")
   :body (str "#binary[location=" location ",size=" size "]")})

(defn- read-bounded
  "Read IS into a byte array, up to `*max-content-size*` bytes.
  Returns the bytes, or nil if the stream exceeds the limit."
  [^InputStream is]
  (let [os (ByteArrayOutputStream.)
        buff (byte-array 8192)]
    (loop [total 0]
      (let [n (.read is buff)]
        (cond
          (neg? n) (.toByteArray os)
          (> (+ total n) *max-content-size*) nil
          :else (do (.write os buff 0 n)
                    (recur (+ total n))))))))

(defn slurp-url-to-content+body
  "Attempts to parse and then to slurp a URL, producing a content-typed response.
  Reads at most `*max-content-size*` bytes; larger resources produce a
  size-only placeholder.  IO failures throw - `handle-slurp` turns them
  into a graceful reply."
  [url-str]
  (when-let [^URL url (try (.toURL (URI. url-str))
                           (catch MalformedURLException _e nil))]
    (if (= (.getProtocol url) "file") ;; expected common case
      (let [^Path p (Paths/get (.toURI url))
            dir? (.isDirectory (io/as-file url))]
        (if dir?
          {:content-type (normalize-content-type "application/octet-stream")
           :body (str "#binary[location=" p ",size=0]")}
          (let [size (Files/size p)]
            (if (> size *max-content-size*)
              (too-large-reply p size)
              (slurp-reply p (normalize-content-type (get-file-content-type p))
                           (Files/readAllBytes p))))))

      ;; It's not a file, so try to fetch it, within bounds
      (let [^URLConnection conn (doto (.openConnection url)
                                  (.setConnectTimeout connection-timeout-ms)
                                  (.setReadTimeout connection-timeout-ms))
            content-type (normalize-content-type
                          (or (.getContentType conn)
                              "application/octet-stream"))]
        (with-open [^InputStream is (.getInputStream conn)]
          (if-let [buff (read-bounded is)]
            (slurp-reply url content-type buff)
            (too-large-reply url (str ">" *max-content-size*))))))))

(defn handle-slurp
  "Message handler which just responds to slurp ops.

  If the slurp is malformed, or fails, replies with a plain-text
  explanation rather than letting the error take down the handler."
  [handler msg]
  (let [{:keys [op url]} msg]
    (if (and (#{"cider/slurp" "slurp"} op) url)
      (do (respond-to msg (try
                            (or (slurp-url-to-content+body url)
                                {:content-type ["text/plain" {}]
                                 :body (str "Don't know how to slurp " url)})
                            (catch Exception e
                              {:content-type ["text/plain" {}]
                               :body (str "Couldn't slurp " url ": "
                                          (or (.getMessage e) (str (class e))))})))
          (respond-to msg :status :done))
      (handler msg))))
