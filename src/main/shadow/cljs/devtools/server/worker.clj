(ns shadow.cljs.devtools.server.worker
  (:refer-clojure :exclude (compile load-file))
  (:require [clojure.core.async :as async :refer (go thread alt!! alt! <!! <! >! >!!)]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.server.system-msg :as sys-msg]
            [shadow.cljs.devtools.server.worker.impl :as impl]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.web.common :as common]
            [shadow.build.classpath :as cp]
            [shadow.build.npm :as npm]
            [shadow.cljs.devtools.server.fs-watch-hawk :as fs-watch]
            [shadow.build.babel :as babel])
  (:import (java.util UUID)))

(defn compile
  "triggers an async compilation, use watch to receive notification about worker state"
  [proc]
  (impl/compile proc))

(defn compile!
  "triggers an async compilation and waits for the compilation result (blocking)"
  [proc]
  (impl/compile! proc))

(defn watch
  "watch all output produced by the worker"
  ([proc chan]
   (impl/watch proc chan true))
  ([proc chan close?]
   (impl/watch proc chan close?)))

(defn start-autobuild
  "automatically compile on file changes"
  [proc]
  (impl/start-autobuild proc))

(defn stop-autobuild [proc]
  (impl/stop-autobuild proc))

(defn sync!
  "ensures that all proc-control commands issued have completed"
  [proc]
  (impl/sync! proc))

(defn repl-runtime-connect
  "called by processes that are able to eval repl commands and report their result

   runtime-out should be a channel that receives things generated by shadow.cljs.repl
   (:repl/invoke, :repl/require, etc)

   returns a channel the results of eval should be put in
   when no more results are coming this channel should be closed"
  [proc runtime-id runtime-out]
  (impl/repl-runtime-connect proc runtime-id runtime-out))

(defn worker-request [{:keys [proc-control state-ref] :as worker} request]
  {:pre [(impl/proc? worker)
         (map? request)
         (keyword? (:type request))]}
  (let [result-chan
        (async/chan 1)

        repl-timeout
        (get-in @state-ref [:build-config :devtools :repl-timeout] 10000)]

    (>!! proc-control (assoc request :result-chan result-chan))

    (try
      (alt!!
        result-chan
        ([x] x)

        ;; FIXME: things that actually take >10s will timeout and never show their result
        (async/timeout repl-timeout)
        ([_]
          {:type :repl/timeout}))

      (catch InterruptedException e
        {:type :repl/interrupt}))))

(defn repl-compile [worker input]
  (worker-request worker
    {:type :repl-compile
     :input input}))

(defn repl-eval [worker client-id input]
  (worker-request worker
    {:type :repl-eval
     :client-id client-id
     :input input}))

(defn load-file [worker {:keys [source file-path] :as file-info}]
  {:pre [(string? file-path)]}
  (worker-request worker
    {:type :load-file
     :source source
     :file-path file-path}))

;; SERVICE API

(defn start
  [config system-bus executor cache-root http classpath npm babel {:keys [build-id] :as build-config}]
  {:pre [(map? http)
         (map? build-config)
         (cp/service? classpath)
         (npm/service? npm)
         (babel/service? babel)
         (keyword? build-id)]}

  (let [proc-id
        (UUID/randomUUID) ;; FIXME: not really unique but unique enough

        _ (log/debug ::start build-id proc-id)

        ;; closed when the proc-stops
        ;; nothing will ever be written here
        ;; its for linking other processes to the server process
        ;; so they shut down when the worker stops
        proc-stop
        (async/chan)

        ;; controls the worker, registers new clients, etc
        proc-control
        (async/chan)

        ;; we put output here
        output
        (async/chan 100)

        ;; clients tap here to receive output
        output-mult
        (async/mult output)

        ;; FIXME: must use buffer, but can't use 1
        ;; when a notify happens and autobuild is running the process may be busy for a while recompiling
        ;; if another fs update happens in the meantime
        ;; and we don't have a buffer the whole config update will block
        ;; if the buffer is too small we may miss an update
        ;; ideally this would accumulate all updates into one but not sure how to go about that
        ;; (would need to track busy state of worker)
        resource-update
        (async/chan (async/sliding-buffer 10))

        asset-update
        (async/chan (async/sliding-buffer 10))

        macro-update
        (async/chan (async/sliding-buffer 10))

        ;; same deal here, 1 msg is sent per build so this may produce many messages
        config-watch
        (async/chan (async/sliding-buffer 100))

        channels
        {:proc-stop proc-stop
         :proc-control proc-control
         :output output
         :resource-update resource-update
         :macro-update macro-update
         :asset-update asset-update
         :config-watch config-watch}

        thread-state
        {::impl/worker-state true
         :http http
         :classpath classpath
         :cache-root cache-root
         :npm npm
         :babel babel
         :proc-id proc-id
         :build-config build-config
         :autobuild false
         :eval-clients {}
         :repl-clients {}
         :pending-results {}
         :channels channels
         :system-bus system-bus
         :executor executor
         :build-state nil}

        state-ref
        (volatile! thread-state)

        thread-ref
        (util/server-thread
          state-ref
          thread-state
          {proc-stop nil
           proc-control impl/do-proc-control
           resource-update impl/do-resource-update
           asset-update impl/do-asset-update
           macro-update impl/do-macro-update
           config-watch impl/do-config-watch}
          {:server-id [::worker build-id]
           :validate
           impl/worker-state?
           :validate-error
           (fn [state-before state-after msg]
             ;; FIXME: handle this better
             (prn [:invalid-worker-result-after (keys state-after) msg])
             state-before)
           :on-error
           (fn [state-before msg ex]
             ;; error already logged by server-thread fn
             state-before)
           :do-shutdown
           (fn [state]
             (>!! output {:type :worker-shutdown :proc-id proc-id})
             state)})

        {:keys [watch-dir watch-exts]
         :or {watch-exts #{"css"}}}
        (:devtools build-config)

        watch-dir
        (or watch-dir
            (get-in build-config [:devtools :http-root]))

        worker-proc
        (-> {::impl/proc true
             :http http
             :proc-stop proc-stop
             :proc-id proc-id
             :proc-control proc-control
             :system-bus system-bus
             :resource-update resource-update
             :macro-update macro-update
             :output output
             :output-mult output-mult
             :thread-ref thread-ref
             :state-ref state-ref}
            (cond->
              (seq watch-dir)
              (assoc :fs-watch
                     (let [watch-dir (io/file watch-dir)]
                       (when-not (.exists watch-dir)
                         (io/make-parents (io/file watch-dir "dummy.html")))
                       (fs-watch/start (:fs-watch config) [watch-dir] watch-exts #(async/>!! asset-update %))))))]

    (sys-bus/sub system-bus ::sys-msg/resource-update resource-update)
    (sys-bus/sub system-bus ::sys-msg/macro-update macro-update)
    (sys-bus/sub system-bus [::sys-msg/config-watch build-id] config-watch)

    ;; ensure all channels are cleaned up properly
    (go (<! thread-ref)
        (async/close! output)
        (async/close! proc-stop)
        (async/close! resource-update)
        (async/close! macro-update)
        (async/close! asset-update)
        (log/debug ::stop build-id proc-id))

    worker-proc))

(defn stop [{:keys [fs-watch] :as proc}]
  {:pre [(impl/proc? proc)]}
  (when fs-watch
    (fs-watch/stop fs-watch))
  (async/close! (:proc-stop proc))
  (<!! (:thread-ref proc)))
