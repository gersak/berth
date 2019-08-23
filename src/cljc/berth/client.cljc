(ns berth.client
  (:require
    #?@(:clj [[cheshire.core :as c]
              [clojure.java.io :as io]])
    [clojure.core.async :as async]
    [berth.client.http :as http
     :refer [with-transform request call]]))


(defn connect 
  ([] (connect nil))
  ([{:keys [host port schema version]
     :or {host "localhost"
          port 2375
          schema "http"}}]
   (reify
     berth.client.http.DockerConnectionProtocol
     (http/host [_] host)
     (http/port [_] port)
     (http/version [_] version)
     (http/schema [_] schema))))


(defn list-images 
  ([connection] (list-images connection nil))
  ([connection params]
   (with-transform c/parse-string
     (call connection :get "images/json" :query params))))

(defn inspect-image [connection image]
  (with-transform c/parse-string
    (call connection :get ["images" image "json"])))

(defn build-image
  ([connection file params]
   (with-transform c/parse-string
     (call connection :post "build"
           :headers {"Conent-Type" "application/x-tar"}
           :query (clojure.set/rename-keys params {:tag :t :quite :q})
           :body #?(:clj (io/file file))))))

(defn remove-image
  [connection image]
  (with-transform c/parse-string
    (call connection :delete ["images" image])))


(comment
  (def connection (connect))
  (async/<!! (call connection :get "_ping"))
  (async/<!! (call connection :get "images/json"))
  (async/<!! (list-images connection))
  (async/<!! (inspect-image connection "neyho/eywa"))
  (async/<!! (build-image connection "eywa.tar.gz" {:tag "neyho/eywa"}))
  (async/<!! (remove-image connection "neyho/eywa:latest")))
