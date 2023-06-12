(ns cider.log.specs
  (:require [cider.log.appender :as appender]
            [cider.log.framework :as framework]
            [cider.log.repl :as repl]
            [clojure.spec.alpha :as s]))

(s/def :cider.log.level/category simple-keyword?)
(s/def :cider.log.level/name simple-keyword?)
(s/def :cider.log.level/object any?)
(s/def :cider.log.level/weight nat-int?)

(s/def :cider.log/level
  (s/keys :req-un [:cider.log.level/category
                   :cider.log.level/name
                   :cider.log.level/object
                   :cider.log.level/weight]))

(s/def :cider.log.filter/end-time pos-int?)

(s/def :cider.log.filter/exceptions
  (s/coll-of string? :kind set?))

(s/def :cider.log.filter/level simple-keyword?)

(s/def :cider.log.filter/loggers
  (s/coll-of string? :kind set?))

(s/def :cider.log.filter/pattern string?)
(s/def :cider.log.filter/start-time pos-int?)

(s/def :cider.log.filter/threads
  (s/coll-of string? :kind set?))

(s/def :cider.log/filters
  (s/keys :opt-un [:cider.log.filter/end-time
                   :cider.log.filter/exceptions
                   :cider.log.filter/level
                   :cider.log.filter/loggers
                   :cider.log.filter/pattern
                   :cider.log.filter/start-time
                   :cider.log.filter/threads]))

(s/def :cider.log.pagination/limit nat-int?)
(s/def :cider.log.pagination/offset nat-int?)

(s/def :cider.log.event/search
  (s/keys :opt-un [:cider.log.pagination/limit
                   :cider.log.pagination/offset
                   :cider.log/filters]))

(s/def :cider.log.framework/add-appender-fn ifn?)
(s/def :cider.log.framework/id string?)
(s/def :cider.log.framework/javadoc-url string?)
(s/def :cider.log.framework/levels (s/coll-of :cider.log/level))
(s/def :cider.log.framework/log-fn ifn?)
(s/def :cider.log.framework/name string?)
(s/def :cider.log.framework/remove-appender-fn ifn?)
(s/def :cider.log.framework/root-logger string?)
(s/def :cider.log.framework/website-url string?)

(s/def :cider.log/framework
  (s/keys :req-un [:cider.log.framework/add-appender-fn
                   :cider.log.framework/id
                   :cider.log.framework/javadoc-url
                   :cider.log.framework/levels
                   :cider.log.framework/log-fn
                   :cider.log.framework/name
                   :cider.log.framework/remove-appender-fn
                   :cider.log.framework/root-logger
                   :cider.log.framework/website-url]))

(s/def :cider.log.appender/id string?)
(s/def :cider.log.appender/levels (s/coll-of :cider.log/level))
(s/def :cider.log.appender/logger string?)
(s/def :cider.log.appender/size pos-int?)
(s/def :cider.log.appender/threshold (s/and nat-int? #(< % 100)))

(s/def :cider.log/appender
  (s/keys :req-un [:cider.log.appender/id]
          :opt-un [:cider.log.appender/levels
                   :cider.log.appender/logger
                   :cider.log.appender/size
                   :cider.log.appender/threshold]))

(s/def :cider.log.consumer/callback ifn?)
(s/def :cider.log.consumer/filter (s/map-of string? any?))
(s/def :cider.log.consumer/id string?)

(s/def :cider.log/consumer
  (s/keys :req-un [:cider.log.consumer/id]
          :opt-un [:cider.log.consumer/callback
                   :cider.log.consumer/filter]))

(s/def :cider.log.event/argument any?)
(s/def :cider.log.event/arguments (s/coll-of :cider.log.event/argument :kind vector?))
(s/def :cider.log.event/id uuid?)
(s/def :cider.log.event/level simple-keyword?)
(s/def :cider.log.event/logger string?)
(s/def :cider.log.event/mdc (s/map-of string? string?))
(s/def :cider.log.event/message string?)
(s/def :cider.log.event/thread string?)
(s/def :cider.log.event/timestamp pos-int?)

(s/def :cider.log/event
  (s/keys :req-un [:cider.log.event/arguments
                   :cider.log.event/id
                   :cider.log.event/level
                   :cider.log.event/logger
                   :cider.log.event/mdc
                   :cider.log.event/message
                   :cider.log.event/thread
                   :cider.log.event/timestamp]))

;; cider.log.framework

(s/fdef framework/add-appender
  :args (s/cat :framework :cider.log/framework :appender :cider.log/appender)
  :ret :cider.log/framework)

(s/fdef framework/add-consumer
  :args (s/cat :framework :cider.log/framework :appender :cider.log/appender :consumer :cider.log/consumer)
  :ret :cider.log/framework)

(s/fdef framework/clear-appender
  :args (s/cat :framework :cider.log/framework :appender :cider.log/appender)
  :ret :cider.log/framework)

(s/fdef framework/consumer
  :args (s/cat :framework :cider.log/framework :appender :cider.log/appender :consumer :cider.log/consumer)
  :ret (s/nilable :cider.log/consumer))

(s/fdef framework/event
  :args (s/cat :framework :cider.log/framework :appender :cider.log/appender :id :cider.log.event/id)
  :ret (s/nilable :cider.log/event))

(s/fdef framework/events
  :args (s/cat :framework :cider.log/framework :appender :cider.log/appender)
  :ret (s/coll-of :cider.log/event))

(s/fdef framework/log
  :args (s/cat :framework :cider.log/framework :event map?)
  :ret nil?)

(s/fdef framework/remove-appender
  :args (s/cat :framework :cider.log/framework :appender :cider.log/appender)
  :ret :cider.log/framework)

(s/fdef framework/remove-consumer
  :args (s/cat :framework :cider.log/framework :appender :cider.log/appender :consumer :cider.log/consumer)
  :ret :cider.log/framework)

(s/fdef framework/update-appender
  :args (s/cat :framework :cider.log/framework :appender :cider.log/appender)
  :ret :cider.log/framework)

(s/fdef framework/resolve-framework
  :args (s/cat :framework-sym qualified-symbol?)
  :ret (s/nilable :cider.log/framework))

(s/fdef framework/resolve-frameworks
  :args (s/or :arity-0 (s/cat)
              :arity-1 (s/cat :framework-syms (s/coll-of qualified-symbol?)))
  :ret (s/map-of :cider.log.framework/id :cider.log/framework))

(s/fdef framework/search-events
  :args (s/cat :framework :cider.log/framework :appender :cider.log/appender :criteria map?)
  :ret (s/coll-of :cider.log/event))

;; cider.log.appender

(s/fdef appender/add-consumer
  :args (s/cat :appender :cider.log/appender :consumer :cider.log/consumer)
  :ret :cider.log/appender)

(s/fdef appender/add-event
  :args (s/cat :appender :cider.log/appender :event :cider.log/event)
  :ret :cider.log/appender)

(s/fdef appender/clear
  :args (s/cat :appender :cider.log/appender)
  :ret :cider.log/appender)

(s/fdef appender/consumers
  :args (s/cat :appender :cider.log/appender)
  :ret (s/coll-of :cider.log/consumer))

(s/fdef appender/consumer-by-id
  :args (s/cat :appender :cider.log/appender :id :cider.log.consumer/id)
  :ret (s/nilable :cider.log/consumer))

(s/fdef appender/event
  :args (s/cat :appender :cider.log/appender :id :cider.log.event/id)
  :ret (s/nilable :cider.log/event))

(s/fdef appender/events
  :args (s/cat :appender :cider.log/appender)
  :ret (s/coll-of :cider.log/event))

(s/fdef appender/make-appender
  :args (s/cat :appender :cider.log/appender)
  :ret :cider.log/appender)

(s/fdef appender/remove-consumer
  :args (s/cat :appender :cider.log/appender :consumer :cider.log/consumer)
  :ret :cider.log/appender)

(s/fdef appender/update-appender
  :args (s/cat :appender :cider.log/appender :settings map?)
  :ret :cider.log/appender)

(s/fdef appender/update-consumer
  :args (s/cat :appender :cider.log/appender :consumer :cider.log/consumer)
  :ret :cider.log/appender)

;; cider.log.repl

(s/def :cider.log.repl.option/framework
  (s/nilable (s/or :string string? :keyword keyword?)))

(s/def :cider.log.repl.option/appender
  (s/nilable (s/or :string string? :keyword keyword?)))

(s/def :cider.log.repl/options
  (s/? (s/nilable (s/keys :opt-un [:cider.log.repl.option/framework
                                   :cider.log.repl.option/appender]))))

(s/fdef repl/add-appender
  :args (s/cat :options :cider.log.repl/options)
  :ret :cider.log/framework)

;; (s/fdef repl/add-consumer
;;   :args (s/cat :framework :cider.log/framework :appender :cider.log/appender :consumer :cider.log/consumer)
;;   :ret :cider.log/framework)

;; (s/fdef repl/clear-appender
;;   :args (s/cat :framework :cider.log/framework :appender :cider.log/appender)
;;   :ret :cider.log/framework)

;; (s/fdef repl/consumer
;;   :args (s/cat :framework :cider.log/framework :appender :cider.log/appender :consumer :cider.log/consumer)
;;   :ret (s/nilable :cider.log/consumer))

;; (s/fdef repl/event
;;   :args (s/cat :framework :cider.log/framework :appender :cider.log/appender :id :cider.log.event/id)
;;   :ret (s/nilable :cider.log/event))

;; (s/fdef repl/events
;;   :args (s/cat :framework :cider.log/framework :appender :cider.log/appender)
;;   :ret (s/coll-of :cider.log/event))

;; (s/fdef repl/log
;;   :args (s/cat :framework :cider.log/framework :event map?)
;;   :ret nil?)

;; (s/fdef repl/remove-appender
;;   :args (s/cat :framework :cider.log/framework :appender :cider.log/appender)
;;   :ret :cider.log/framework)

;; (s/fdef repl/remove-consumer
;;   :args (s/cat :framework :cider.log/framework :appender :cider.log/appender :consumer :cider.log/consumer)
;;   :ret :cider.log/framework)

(s/fdef repl/set-appender!
  :args (s/cat :framework (s/or :string string? :keyword keyword?))
  :ret nil?)

(s/fdef repl/set-framework!
  :args (s/cat :framework (s/or :string string? :keyword keyword?))
  :ret nil?)

;; (s/fdef repl/update-appender
;;   :args (s/cat :framework :cider.log/framework :appender :cider.log/appender)
;;   :ret :cider.log/framework)

;; (s/fdef repl/resolve-framework
;;   :args (s/cat :framework-sym qualified-symbol?)
;;   :ret (s/nilable :cider.log/framework))

;; (s/fdef repl/resolve-frameworks
;;   :args (s/or :arity-0 (s/cat)
;;               :arity-1 (s/cat :framework-syms (s/coll-of qualified-symbol?)))
;;   :ret (s/map-of :cider.log.framework/id :cider.log/framework))

;; (s/fdef repl/search-events
;;   :args (s/cat :framework :cider.log/framework :appender :cider.log/appender :criteria map?)
;;   :ret (s/coll-of :cider.log/event))
