(ns berth.client.http
  #?(:cljs (:require-macros [cljs.core.async.macros :as async]))
  #?(:clj
     (:require
       [clojure.core.async :as async]
       [clj-http.client :as client])
     :cljs 
     (:require 
       [clojure.core.async :as async]
       [clojure.string]
       [clojure.set])))


(def ^:dynamic *timeout* nil)

#?(:cljs (def http (js/require "http")))

#?(:cljs
   (defn process-meta [response]
     (when response
       (let [keywords '("headers" "url" "httpVersion" "method" "statusCode" "statusMessage")
           response' (reduce 
                       #(assoc %1 %2 (goog.object/get response %2))
                       nil 
                       keywords)]
       (->
         response'
         (clojure.set/rename-keys
           {"headers" :headers
            "url" :url
            "version" :version
            "method" :request-method
            "statusCode" :status
            "statusMessage" :message})
         (update :headers js->clj)
         (update :request-method #(when % ((comp keyword clojure.string/lower-case) %))))))))


#?(:clj
   (defn request [{{accept "Accept"} :headers 
                   :or {accept "application/json"}
                   :as params}]
     (let [result (async/promise-chan)] 
       (try
         (let [r (client/request params)]
           (async/put! result r))
         (catch Throwable e
           (async/put! result e))
         (finally (async/close! result))) 
       result))
   :cljs
   (defn request [params]
     (let [{:keys [url method headers timeout body source-address]
            :or {method "GET"
                 body ""
                 timeout 180000}} params
           [protocol r] (clojure.string/split url #"//")
           [host-port & path] (clojure.string/split r #"/")
           [host port] (clojure.string/split host-port #":")
           path' (str "/" (clojure.string/join "/" path))
           method (clojure.string/upper-case (name method))
           result (async/promise-chan)
           meta-response (async/promise-chan)
           buffer (async/chan 100)
           headers (cond-> headers
                     (seq body) (assoc "Content-Length" (js/Buffer.byteLength body)))
           params (cond-> 
                    {:protocol protocol
                     :headers headers
                     :host host
                     :method method
                     :path path'
                     :port (or (when port (str port)) "80")
                     :timeout (or *timeout* timeout)}
                    source-address (assoc :localAddress source-address))
           ; _ (.log js/console (str params))
           params'  (clj->js params)
           req (.request http
                         params'
                         (fn [^js resp]
                           (let [headers (js->clj (.-headers resp))
                                 status (.-statusCode resp)
                                 meta {:headers headers 
                                       :status status}]
                             ; (println "Response: " headers status)
                             (async/put! meta-response meta))
                           (.setEncoding resp "utf8")
                           (.on resp "data" #(do
                                               ; (.log js/console "Type: " (type %))
                                               ; (.log js/console "Received: " %)
                                               (async/put! buffer %)))
                           (.on resp "end" #(async/close! buffer))))]
       (.on req "error" (fn [e]
                          ; (.log js/console 
                          ;   (str "Error while sending request to " url "\n" (pr-str params') \newline e))
                          (async/close! buffer)
                          (async/put! result (ex-info
                                               (.-message e)
                                               {:protocol protocol
                                                :headers headers
                                                :hostname host
                                                :method method
                                                :path path'
                                                :port (or (when port (js/parseInt port)) 80)
                                                :timeout (or *timeout* timeout)
                                                :body body
                                                :message (.-message e)}))
                          (async/close! result)))
       (async/go-loop [chunky (async/<! buffer)
                       body nil]
         (if-not chunky 
           (do
             (async/put! result (async/<! meta-response))
             (async/close! result))
           (recur (async/<! buffer) (str body chunky))))
       (.end req body)
       result)))


(defprotocol ->URLProtocol
  (->URL [this] "Returns URL represenation of object."))

(extend-protocol ->URLProtocol
  #?(:clj clojure.lang.Keyword
     :cljs cljs.core.Keyword)
  (->URL [this] (name this))
  #?(:clj clojure.lang.Symbol
     :cljs cljs.core.Symbol)
  (->URL [this] (name this))
  #?(:clj clojure.lang.PersistentVector
     :cljs cljs.core.PersistentVector)
  (->URL [this] (clojure.string/join "/" (map ->URL (remove nil? this))))
  #?(:clj clojure.lang.PersistentList
     :cljs cljs.core.List)
  (->URL [this] (clojure.string/join "/" (map ->URL (remove nil? this))))
  #?(:clj java.lang.String
     :cljs string)
  (->URL [this] this)
  #?(:clj java.lang.Number
     :cljs number)
  (->URL [this] (str this))
  nil
  (->URL [this] ""))

(def ^:dynamic *call-transform* nil)


(defprotocol DockerConnectionProtocol
  (host [this] "Returns connection host")
  (port [this] "Returns conneciton port")
  (alive? [this] "Return true if deamon is alive")
  (schema [this] "Returns connection schema (http,https,unix)")
  (version [this] "Returns connection version!"))

(defn call
  ([connection method path-args 
    & {:keys [query body headers]
       :or {headers {"Accept" "application/json"
                     "Content-Type" "application/json"}}}]
   (let [host (host connection)
         port (port connection)
         schema (schema connection)
         version (version connection)
         path (->URL path-args)
         url (if version
               (#?(:clj format
                   :cljs goog.string.format) "%s://%s:%s/%s/%s" schema host port version path)
               (#?(:clj format
                   :cljs goog.string.format) "%s://%s:%s/%s" schema host port path))
         ;; This is here only because of geckoconnection pre 18.0.
         result (async/promise-chan)
         params (cond->
                  {:url url
                   :method method}
                  (some? query) (assoc :query-params query)
                  (some? body) (assoc :body body)
                  (some? headers) (assoc :headers headers))
         transform *call-transform*]
     (async/go
       (let [{body :body :as response} (async/<! (request params))]
         (if (instance? #?(:clj Throwable :cljs js/Error) response) 
           (do
             (async/put! result response)
             (async/close! result))
           (try
             (if-not body nil
               (async/put! result 
                           (if transform
                             (transform body)
                             body)))
             (catch Throwable e
               (async/put! result e))
             (finally (async/close! result))))))
     result)))


(defmacro with-transform [transform & body]
  `(binding [*call-transform* ~transform]
     ~@body))

(defmacro with-timeout [timeout & body]
  `(binding [*timeout* ~timeout]
     ~@body))
