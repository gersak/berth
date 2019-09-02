(ns berth.client
  (:require
    #?@(:clj [[clojure.data.json :as json]
              [clojure.java.io :as io]])
    [clojure.core.async :as async]
    [berth.client.http :as http
     :refer [with-transform request call]]))


(def connect http/connect)

;; IMAGES
(def body->json
  #?(:clj json/read-str))

(defn ping 
  [connection]
  (call connection :get "_ping"))

(defn list-images 
  ([connection] (list-images connection nil))
  ([connection params]
   (with-transform body->json
     (call connection :get "images/json" :query params))))

(defn inspect-image [connection image]
  (with-transform body->json 
    (call connection :get ["images" image "json"])))

(defn build-image
  ([connection file params]
   (with-transform body->json
     (call connection :post "build"
           :headers {"Conent-Type" "application/x-tar"}
           :query (clojure.set/rename-keys params {:tag :t :quite :q})
           :body #?(:clj (io/file file))))))

(defn remove-image
  ([connection image]
   (remove-image connection image nil))
  ([connection image params]
  (with-transform body->json
    (call connection :delete ["images" image]
          :query params))))

(defn prune-images 
  ([connection image]
   (prune-images connection image nil))
  ([connection image params]
   (with-transform body->json
     (call connection :post "images/prune"
           :query params))))

(defn image-history
  [connection image]
  (with-transform body->json
    (call connection :get ["images" image "history"])))

;; CONTAINERS

(defn list-containers 
  ([connection] (list-containers connection nil))
  ([connection params]
   (with-transform body->json
     (call connection :get "containers/json"
           :query params))))

(defn create-container
  ([connection name params]
   (with-transform body->json
     (call connection :post "containers/create"
           :query {:name name}
           :body #?(:clj (json/write-str params))))))

(defn inspect-container
  ([connection id]
   (with-transform body->json
     (call connection :get ["containers" id "json"]))))

(defn update-container
  ([connection id params]
   {:pre [(some? id)]}
   (with-transform body->json
     (call connection :post ["containers" id "update"]
           :query {:name name}
           :body #?(:clj (json/write-str params))))))

(defn start-container 
  ([connection id] (start-container connection id nil))
  ([connection id dkeys]
   {:pre [(some? id)]}
   (with-transform body->json
     (call connection :post ["containers" id "start"]
           :query (when (some? dkeys) {"detachKeys" dkeys})))))

(defn pause-container
  ([connection id]
   {:pre [(some? id)]}
   (with-transform body->json
     (call connection :post ["containers" id "pause"]))))

(defn unpause-container
  ([connection id]
   {:pre [(some? id)]}
   (with-transform body->json
     (call connection :post ["containers" id "unpause"]))))

(defn stop-container 
  ([connection id] (stop-container connection id nil))
  ([connection id timeout]
   {:pre [(some? id)]}
   (with-transform body->json
     (call connection :post ["containers" id "stop"]
           :query (when (some? timeout) {:t timeout})))))

(defn restart-container 
  ([connection id] (stop-container connection id nil))
  ([connection id timeout]
   {:pre [(some? id)]}
   (with-transform body->json
     (call connection :post ["containers" id "restart"]
           :query (when (some? timeout) {:t timeout})))))

(defn kill-container 
  ([connection id] (stop-container connection id nil))
  ([connection id timeout]
   {:pre [(some? id)]}
   (with-transform body->json
     (call connection :post ["containers" id "kill"]
           :query (when (some? timeout) {:t timeout})))))


(defn prune-containers
  ([connection] (prune-containers connection nil))
  ([connection params]
   (with-transform body->json
     (call connection :post "containers/prune"
           :query params))))


;; TODO - ATTACHING

(defn remove-container
  ([connection id] (remove-container connection id nil))
  ([connection id params]
   (with-transform body->json
     (call connection :delete ["containers" id]
           :query params))))

;; TODO - Implement missing functions


;; VOLUMES

(comment
  (def connection (connect))
  (async/<!! (call connection :get "_ping"))
  (async/<!! (call connection :get "images/json"))
  (async/<!! (list-images connection))
  (async/<!! (inspect-image connection "neyho/eywa"))
  (async/<!! (build-image connection "eywa.tar.gz" {:tag "neyho/eywa"}))
  (async/<!! (remove-image connection "neyho/eywa:latest"))
  (async/<!! (image-history connection "neyho/eywa:latest"))
  (async/<!! (list-containers connection {:all true}))
  (async/<!!
    (create-container 
      connection 
      "EYWA"
      {"Image" "neyho/eywa:latest"
       "ExposedPorts" {"8000/tcp" {}}
       "PortBindings" {"8000/tcp" [{"HostPort" "8000"}]}}))
  (async/<!! (inspect-container connection (x "Id")))
  (async/<!! (start-container connection (x "Id")))
  (async/<!! (stop-container connection (x "Id")))
  (async/<!! (remove-container connection (x "Id")))
  (async/<!! (prune-containers connection)))
