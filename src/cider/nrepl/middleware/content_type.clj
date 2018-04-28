(ns cider.nrepl.middleware.content-type
  "Rich content handling for CIDER.
  Mostly derived from the pprint middleware.

  ---

  In the long ago, @technomancy [1] talked about his vision for using
  nREPL to support multimedia results beyond plain text, ala DrRacket
  and other \"rich\" REPLs. There was an initial cut at this [2],
  which never became part of the mainline Emacs tooling.

  The goal of this module is to provide some support for recognizing
  multimedia objects (images and URIs thereto) as the result of
  evaluation, so that they can be rendered by a REPL.

  The design of this module is based heavily on RFC-2045 [3] which
  describes messages packaged with `Content-Type`,
  `Content-Transfer-Encoding` and of course a body in that it seeks to
  provide decorated responses which contain metadata which a client
  can use to provide a rich interpretation.

  There's also RFC-2017 [4] which defines the `message/external-body`
  MIME type for defining messages which don't contain their own
  bodies.

  The basic architecture of this changeset is that eval results are
  inspected, and matched against two fundamental supported cases. One
  is that the value is actually a binary Java image, which can be MIME
  encoded and transmitted back directly. The other is that the object
  is some variant of a URI (such as a file naming an image or other
  content) which cannot be directly serialized. In this second case we
  send an RFC-2017 response which provides the URL from which a client
  could request the nREPL server slurp the desired content.

  Hence the slurp middleware which slurps URLs and produces MIME coded
  data.

  ---

  [1] https://groups.google.com/forum/#!topic/clojure-tools/rkmJ-5086RY
  [2] https://github.com/technomancy/nrepl-discover/blob/master/src/nrepl/discover/samples.clj#L135
  [3] https://tools.ietf.org/html/rfc2045
  [4] https://tools.ietf.org/html/rfc2017"
  {:authors ["Reid 'arrdem' McKenzie <me@arrdem.com>"]}
  (:require [cider.nrepl.middleware.slurp :refer [slurp-reply]])
  (:import clojure.tools.nrepl.transport.Transport
           java.awt.Image
           [java.io ByteArrayOutputStream File OutputStream]
           [java.net URI URL]
           java.nio.file.Path
           javax.imageio.ImageIO))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol URLCoercable
  (as-url [o]))

(extend-protocol URLCoercable
  Path
  (as-url [^Path p]
    (.. p normalize toUri toURL))

  File
  (as-url [^File f]
    (.. f getCanonicalFile toURI toURL))

  URI
  (as-url [^URI u]
    (.. u toURL))

  URL
  (as-url [^URL u]
    u))

(defn response+content-type
  "Consumes an nREPL response, having a `:value`. If the `:value` is
  recognized as an AWT Image, a File, or a File URI, rewrite the
  response to have a `:content-type` being a MIME type of the content,
  and a `:body` to re-use the RFC term for the message payload."
  [{:keys [session value] :as response}]
  (cond
    ;; FIXME (arrdem 2018-04-03):
    ;; 
    ;;   This could be more generic in terms of tolerating more
    ;;   protocols / schemes

    ;; RFC-2017 external-body responses for UR[IL]s and things which are just wrappers thereof
    (or (and (instance? File value)
             (.exists ^File value))
        (and (instance? URI value)
             #_(= (.getScheme ^URI value) "file"))
        (and (instance? URL value)
             #_(#{"jar" "file"} (.getProtocol ^URL value))))
    (assoc response
           :content-type ["message/external-body"
                          {"access-type" "URL"
                           "URL" (.toString (as-url value))}]
           :body "")

    ;; FIXME (arrdem 2018-04-03):
    ;; 
    ;;   This is super snowflakey in terms of only supporting base64
    ;;   coding this one kind of object.  This could definitely be
    ;;   more generic / open to extension but hey at least it's
    ;;   re-using machinery.

    (instance? java.awt.Image value)
    (with-open [bos (ByteArrayOutputStream.)]
      (ImageIO/write ^Image value "png" ^OutputStream bos)
      (merge response
             (slurp-reply ["image/png" {}] (.toByteArray bos))))

    :else response))

(defn content-type-transport
  "Transport proxy which allows this middleware to intercept responses
  and inspect / alter them."
  [^Transport transport]
  (reify Transport
    (recv [this]
      (.recv transport))
    (recv [this timeout]
      (.recv transport timeout))
    (send [this response]
      (.send transport (response+content-type response)))))

(defn handle-content-type
  "Handler for inspecting the results of the `eval` op, attempting to
  detect content types and generate richer responses when content
  information is available.

  Requires that the user opt-in by providing the `content-type` key in
  nREPL requests, same as the pprint middleware.

  Note that this middleware makes no attempt to prevent
  pretty-printing of the eval result, which could lead to double
  output in some REPL clients."
  [handler msg]
  (let [{:keys [op transport content-type]} msg]
    (handler (if (and (= "eval" op) content-type)
               (assoc msg :transport (content-type-transport transport))
               msg))))
