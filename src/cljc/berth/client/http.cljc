(ns reacher.util.http
  #?(:cljs (:require-macros [cljs.core.async.macros :as async]))
  #?(:clj
     (:require
       [clojure.core.async :as async]
       [cheshire.core :as json]
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

(defn- read-body [content-type body]
  (when body
    (condp re-find (str content-type)
      #"application/json" #?(:clj (json/parse-string
                                    (if (string? body)
                                      body
                                      (slurp body)) 
                                    true)
                             :cljs (js->clj (js/JSON.parse body) :keywordize-keys true))
      body)))

#?(:clj
   (defn request [{{accept "Accept"} :headers 
                   :or {accept "application/json"}
                   :as params}]
     (let [result (async/promise-chan)] 
       (try
         (let [r (client/request params)]
           (async/put! result (update :body r (read-body accept))))
         (catch Throwable e
           (async/put! result e))) 
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
                                                :message (.-message e)}))))
       (async/go-loop [chunky (async/<! buffer)
                       body nil]
         (if-not chunky (async/put! result 
                                    (let [m (async/<! meta-response)]
                                      (assoc m :body (read-body 
                                                       (get-in m [:headers "content-type"])
                                                       body))))
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

(defn call
  ([driver method path-args]
   (call driver method path-args nil))
  ([driver method path-args & {:keys [query body headers]
                               :or {headers {"Accept" "application/json"
                                             "Content-Type" "application/json"}}}]
   (let [host (drivers/get-host driver)
         port (drivers/get-port driver)
         root-path (drivers/get-root-path driver)
         path (->URL path-args)
         url (if root-path
               (#?(:clj format
                   :cljs goog.string.format) "http://%s:%s/%s/%s" host port root-path path)
               (#?(:clj format
                   :cljs goog.string.format) "http://%s:%s/%s" host port path))
         ;; This is here only because of geckodriver pre 18.0.
         result (async/promise-chan)
         params {:url url
                 :method method
                 :headers headers 
                 :timeout (* 1000 *timeout*)
                 :body body}
         transform *call-transform*]
     (go
       (let [{{:keys [status] :as body} :body :as response} (async/<! (http/request params))]
         (if (instance? #?(:clj Throwable :cljs js/Error) response) 
           (do
             (async/put! result response)
             (async/close! result))
           (try
             (async/put! result (if-not body
                                  {}
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
