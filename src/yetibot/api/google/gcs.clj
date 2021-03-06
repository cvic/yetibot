;; "Reference:
;;  https://github.com/urbandictionary/gcs-pow/blob/5f5b55bbc2d60419938ea44c88491fa1bf032217/src/gcs/core.clj"
(ns yetibot.api.google.gcs
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.spec.alpha :as s]
            [yetibot.core.config :refer [get-config]])
  (:import com.google.auth.oauth2.ServiceAccountCredentials
           [com.google.cloud.storage Blob$BlobSourceOption BlobId
            Storage$BlobListOption Storage$BucketListOption StorageOptions]))

(s/def ::config any?)

(defn config [] (get-config ::config [:google]))

(def blob-source-option (Blob$BlobSourceOption/generationMatch))

(defn creds-io-stream []
  (-> (config) :value :service :account
      .getBytes
      io/input-stream))

(def storage-client
  (delay
    (-> (StorageOptions/newBuilder)
        (.setCredentials (ServiceAccountCredentials/fromStream (creds-io-stream)))
        .build
        .getService)))

(defn get-object [bucket object-name]
  (.get @storage-client (BlobId/of bucket object-name)))

(defn buckets
  "Returns list of buckets"
  []
  (doall
    (for [b (->> (.list @storage-client
                        (into-array [(Storage$BucketListOption/pageSize 100)]))
                 .iterateAll)]
      (bean b))))

(defn content [path]
  (let [[bucket object-name] (string/split path #"\/" 2)]
    (-> (get-object bucket object-name)
        (.getContent (into-array [(Blob$BlobSourceOption/generationMatch)]))
        String.)))


(defn list-objects [bucket prefix]
  (->> (.list @storage-client bucket
              (into-array [(Storage$BlobListOption/prefix prefix)
                           ]))
       (.iterateAll)
       (map bean)))

