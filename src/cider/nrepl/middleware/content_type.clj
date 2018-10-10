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
  {:authors ["Reid 'arrdem' McKenzie <me@arrdem.com>"
             "Arne 'plexus' Brasseur <arne@arnebrasseur.net>"]}
  (:require
   [cider.nrepl.middleware.slurp :refer [slurp-reply]])
  (:import
   [java.awt.image RenderedImage]
   [java.io ByteArrayOutputStream File OutputStream]
   [java.net URI URL]
   java.nio.file.Path
   javax.imageio.ImageIO
   nrepl.transport.Transport))

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

(defn external-body-response
  "Partial response map having an external-body content-type referring to the given URL.

  See RFC-2017: Definition of the URL MIME External-Body Access-Type."
  [value]
  {:content-type ["message/external-body"
                  {"access-type" "URL"
                   "URL" (.toString ^URL (as-url value))}]
   :body ""})

(defmulti content-type-response
  "Consumes an nREPL response, having a `:value`. If the `:value` is of a
  recognized type, then rewrite the response to have a `:content-type` being a
  MIME type of the content, and a `:body` to re-use the RFC term for the message
  payload.

  Dispatches on the [[clojure.core/type]] of the value, i.e. the metadata
  `:type` value, or the class."
  (comp type :value))

(defmethod content-type-response :default [response]
  response)

(defmethod content-type-response URI [{:keys [value] :as response}]
  (merge response (external-body-response value)))

(defmethod content-type-response URL [{:keys [value] :as response}]
  (merge response (external-body-response value)))

(defmethod content-type-response File [{^File file :value :as response}]
  (if (.exists file)
    (merge response (external-body-response file))
    response))

(defmethod content-type-response java.awt.Image [{^java.awt.Image image :value :as response}]
  (with-open [bos (ByteArrayOutputStream.)]
    (ImageIO/write image "png" ^OutputStream bos)
    (merge response (when (ImageIO/write ^RenderedImage value "png" ^OutputStream bos)
                      (slurp-reply "" ["image/png" {}] (.toByteArray bos))))))

(defn content-type-transport
  "Transport proxy which allows this middleware to intercept responses
  and inspect / alter them."
  [^Transport transport]
  (reify Transport
    (recv [_this]
      (.recv transport))

    (recv [_this timeout]
      (.recv transport timeout))

    (send [this response]
      (.send transport (content-type-response response)))))

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
