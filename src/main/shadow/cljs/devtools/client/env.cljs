(ns shadow.cljs.devtools.client.env
  (:require
    [goog.object :as gobj]
    [clojure.string :as str]
    [cljs.tools.reader :as reader]
    [cljs.pprint :refer (pprint)]
    [clojure.set :as set]))

(defonce active-modules-ref
  (volatile! #{}))

(defn module-loaded [name]
  (vswap! active-modules-ref conj (keyword name)))

(defn module-is-active? [module]
  (contains? @active-modules-ref module))

;; FIXME: make this persistent somehow?
(defonce runtime-id (str (random-uuid)))

(goog-define enabled false)

(goog-define autoload true)

(goog-define module-format "goog")

(goog-define reload-with-state false)

(goog-define build-id "")

(goog-define proc-id "")

(goog-define server-host "")

(goog-define server-port 8200)

(goog-define repl-pprint false)

(goog-define use-document-host true)

(goog-define devtools-url "")

(goog-define reload-strategy "optimized")

(goog-define ssl false)

(goog-define ignore-warnings false)

(goog-define log-style "font-weight: bold;")

(goog-define custom-notify-fn "")

(defn devtools-info []
  #js {:server_port server-port
       :server_host server-host
       :build_id build-id
       :proc_id proc-id
       :runtime_id runtime-id
       :ssl ssl})

(defn get-server-host []
  (cond
    (and use-document-host
         js/goog.global.document
         js/goog.global.document.location
         (seq js/goog.global.document.location.hostname))
    js/document.location.hostname

    (seq server-host)
    server-host

    :else
    "localhost"))

(defn get-url-base []
  (if (seq devtools-url)
    devtools-url
    (str "http" (when ssl "s") "://" (get-server-host) ":" server-port)))

(defn get-ws-url-base []
  (-> (get-url-base)
      (str/replace #"^http" "ws")))

(defn ws-url [runtime-type]
  {:pre [(keyword? runtime-type)]}
  (str (get-ws-url-base) "/ws/worker/" build-id "/" proc-id "/" runtime-id "/" (name runtime-type)))

(defn ws-listener-url [client-type]
  (str (get-ws-url-base) "/ws/listener/" build-id "/" proc-id "/" runtime-id))

(defn files-url []
  (str (get-url-base) "/worker/files/" build-id "/" proc-id "/" runtime-id))

(def repl-print-fn
  (if-not repl-pprint
    pr-str
    (fn repl-pprint [obj]
      (with-out-str
        (pprint obj)
        ))))

(defn repl-error [e]
  (-> {:type :repl/invoke-error
       ;; FIXME: may contain non-printable things and would break the client read
       ;; :ex-data (ex-data e)
       :error (.-message e)}
      (cond->
        (.hasOwnProperty e "stack")
        (assoc :stack (.-stack e)))))

(defonce repl-results-ref (atom {}))

(defn repl-call [repl-expr repl-error]
  (try
    (let [result-id (str (random-uuid))
          result {:type :repl/result
                  :result-id result-id}

          start (js/Date.now)
          ret (repl-expr)
          runtime (- (js/Date.now) start)]

      ;; FIXME: this needs some kind of GC, shouldn't keep every single result forever
      (swap! repl-results-ref assoc result-id {:timestamp (js/Date.now)
                                               :result ret})

      ;; FIXME: these are nonsense with multiple sessions. refactor this properly
      (set! *3 *2)
      (set! *2 *1)
      (set! *1 ret)

      (try
        (let [printed (repl-print-fn ret)]
          (swap! repl-results-ref assoc-in [result-id :printed] printed)
          (assoc result :value printed :ms runtime))
        (catch :default e
          (js/console.log "encoding of result failed" e ret)
          (assoc result :error "ENCODING FAILED, check host console"))))
    (catch :default e
      (set! *e e)
      (repl-error e)
      )))

;; FIXME: this need to become idempotent somehow
;; but is something sets a print-fn we can't tell if that
;; will actually call ours. only a problem if the websocket is
;; reconnected though
(defonce reset-print-fn-ref (atom nil))

(defn set-print-fns! [msg-fn]
  ;; cannot capture these before as they may change in between loading this file
  ;; and running the websocket connect. the user code is loaded after this file
  (let [original-print-fn cljs.core/*print-fn*
        original-print-err-fn cljs.core/*print-err-fn*]

    (reset! reset-print-fn-ref
      (fn reset-print-fns! []
        (set-print-fn! original-print-fn)
        (set-print-err-fn! original-print-err-fn)))

    (set-print-fn!
      (fn repl-print-fn [& args]
        (msg-fn {:type :repl/out :text (str/join "" args)})
        (when original-print-fn
          (apply original-print-fn args))))

    (set-print-err-fn!
      (fn repl-print-err-fn [& args]
        (msg-fn {:type :repl/err :text (str/join "" args)})
        (when original-print-err-fn
          (apply original-print-err-fn args))))))

(defn reset-print-fns! []
  (when-let [x @reset-print-fn-ref]
    (x)
    (reset! reset-print-fn-ref nil)))

(defn patch-goog! []
  (when (= "goog" module-format)
    ;; patch away the already declared exception
    (set! js/goog.provide js/goog.constructNamespace_)
    ;; goog.module calls this directly
    (set! js/goog.isProvided_ (constantly false))))

(def async-ops #{:repl/require :repl/init :repl/session-start})

(def repl-queue-ref (atom false))
(defonce repl-queue-arr (array))

(defn process-next! []
  (when-not @repl-queue-ref
    (when-some [task (.shift repl-queue-arr)]
      (reset! repl-queue-ref true)
      (task))))

(defn done! []
  (reset! repl-queue-ref false)
  (process-next!))

;; do work that all client impls would share here instead of repeating the code
(defn preprocess-ws-msg [{:keys [type] :as msg}]
  (case type
    :build-complete
    (let [{:keys [info]} msg

          warnings
          (->> (for [{:keys [resource-name warnings] :as src} (:sources info)
                     :when (not (:from-jar src))
                     warning warnings]
                 (assoc warning :resource-name resource-name))
               (distinct)
               (into []))]
      (assoc-in msg [:info :warnings] warnings))

    msg))

(def custom-notify-types
  #{:build-complete
    :build-failure
    :build-init
    :build-start})

(defn run-custom-notify! [msg]
  (when (seq custom-notify-fn)
    ;; look up every time it case it gets reloaded
    (let [fn (js/goog.getObjectByName custom-notify-fn js/$CLJS)]
      (if-not (fn? fn)
        (js/console.warn "couldn't find custom :build-notify" custom-notify-fn)
        (try
          (fn msg)
          (catch :default e
            (js/console.error "Failed to run custom :build-notify" custom-notify-fn)
            (js/console.error e))))))
  (done!))

(defn process-ws-msg [text handler]
  (binding [reader/*default-data-reader-fn*
            (fn [tag value]
              [:tagged-literal tag value])]
    (try
      (let [msg (-> text (reader/read-string) (preprocess-ws-msg))]
        (.push repl-queue-arr #(handler msg done!))
        ;; ensure custom notify is called after the message is handled by shadow-cljs itself
        ;; FIXME: :build-complete isn't actually treated as async so this runs before the code is reloaded
        ;; only actually an issue when reloading the ns that has the notify-fn since the old one will be called
        (when (contains? custom-notify-types (:type msg))
          (.push repl-queue-arr #(run-custom-notify! msg))))
      (process-next!)
      (catch :default e
        (js/console.warn "failed to parse websocket message" text e)
        (throw e)))))

(defn make-task-fn [{:keys [log-missing-fn log-call-async log-call]} {:keys [fn-sym fn-str async]}]
  (fn [next]
    (try
      (let [fn-obj (js/goog.getObjectByName fn-str js/$CLJS)]
        (cond
          (nil? fn-obj)
          (do (when log-missing-fn
                (log-missing-fn fn-sym))
              (next))

          async
          (do (when log-call-async
                (log-call-async fn-sym))
              (fn-obj next))

          :else
          (do (when log-call
                (log-call fn-sym))
              (fn-obj)
              (next))))
      (catch :default ex
        (js/console.warn "error when calling lifecycle function" (str fn-sym) ex)
        (next)))))

(defn do-js-reload* [failure-fn [task & remaining-tasks]]
  (when task
    (try
      (task #(do-js-reload* failure-fn remaining-tasks))
      (catch :default e
        (failure-fn e task remaining-tasks)))))

(defn do-js-reload
  "should pass the :build-complete message and an additional callback
   which performs the actual loading of the code (sync)
   will call all before/after callbacks in order"
  ([msg load-code-fn]
   (do-js-reload
     msg
     load-code-fn
     (fn [])))
  ([msg load-code-fn complete-fn]
   (do-js-reload
     msg
     load-code-fn
     complete-fn
     (fn [error task remaining]
       (js/console.warn "JS reload failed" error))))
  ([{:keys [reload-info] :as msg} load-code-fn complete-fn failure-fn]
   (let [load-tasks
         (-> []
             ;; unload is FILO
             (into (->> (:before-load reload-info)
                        (map #(make-task-fn msg %))
                        (reverse)))
             (conj (fn [next]
                     (load-code-fn)
                     (next)))
             ;; load is FIFO
             (into (map #(make-task-fn msg %)) (:after-load reload-info))
             (conj (fn [next]
                     (complete-fn)
                     (next))))]

     (do-js-reload* failure-fn load-tasks))))

(defn before-load-src [{:keys [type ns] :as src}]
  (when (= :cljs type)
    (doseq [x js/goog.global.SHADOW_NS_RESET]
      (x ns))))

(defonce custom-msg-subscribers-ref (atom {}))

(defn subscribe! [sub-id callback]
  (swap! custom-msg-subscribers-ref assoc sub-id callback))

(defn publish! [msg]
  (doseq [[id callback] @custom-msg-subscribers-ref]
    (try
      (callback msg)
      (catch :default e
        (js/console.warn "failed to handle custom msg" id msg)))))

(defn goog-is-loaded? [name]
  (js/$CLJS.SHADOW_ENV.isLoaded name))

(def goog-base-rc
  [:shadow.build.classpath/resource "goog/base.js"])

(defn src-is-loaded? [{:keys [resource-id output-name] :as src}]
  ;; FIXME: don't like this special case handling, but goog/base.js will always be loaded
  ;; but not as a separate file
  (or (= goog-base-rc resource-id)
      (goog-is-loaded? output-name)))

(defn prefilter-sources [reload-info sources]
  (->> sources
       (filter
         (fn [{:keys [module] :as rc}]
           (or (= "js" module-format)
               (module-is-active? module))))
       ;; don't reload namespaces that have ^:dev/never-reload meta
       (remove (fn [{:keys [ns]}]
                 (contains? (:never-load reload-info) ns)))))

(defn filter-sources-to-get-optimized [{:keys [sources compiled] :as info} reload-info]
  (->> sources
       (prefilter-sources reload-info)
       (filter
         (fn [{:keys [ns resource-id] :as src}]
           (or (contains? (:always-load reload-info) ns)
               (not (src-is-loaded? src))
               (and (contains? compiled resource-id)
                    ;; never reload files from jar
                    ;; they can't be hot-swapped so the only way they get re-compiled
                    ;; is if they have warnings, which we can't to anything about
                    (not (:from-jar src))))))
       (into [])))

(defn filter-sources-to-get-full [{:keys [sources compiled] :as info} reload-info]
  (loop [affected #{}
         sources-to-get []
         [src & more] (prefilter-sources reload-info sources)]

    (if-not src
      sources-to-get
      (let [{:keys [ns resource-id deps provides]}
            src

            should-reload?
            (or (contains? (:always-load reload-info) ns)
                ;; always load sources that haven't been loaded yet
                ;; this fixes issues where a namespace is added to a build that has
                ;; dependencies that haven't been loaded yet but were compiled before
                (not (src-is-loaded? src))
                (and (or (contains? compiled resource-id)
                         (some affected deps))
                     ;; never reload files from jar
                     ;; they can't be hot-swapped so the only way they get re-compiled
                     ;; is if they have warnings, which we can't to anything about
                     (not (:from-jar src))))]

        (if-not should-reload?
          (recur affected sources-to-get more)
          (recur
            (set/union affected provides)
            (conj sources-to-get src)
            more))))))

(defn filter-reload-sources [info reload-info]
  (if (= "full" reload-strategy)
    (filter-sources-to-get-full info reload-info)
    (filter-sources-to-get-optimized info reload-info)))
