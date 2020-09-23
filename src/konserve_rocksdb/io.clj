(ns konserve-rocksdb.io
  "IO function for interacting with database"
  (:require [clj-rocksdb :as rdb]))

(set! *warn-on-reflection* 1)

(defn split-header [^"[B" bytes]
  (when bytes
    (let [data'  (vec bytes)
          data [(take 4 data') (->> data' (take 8) (drop 4)) (drop 8 data')]
          streamer (fn [header meta-size data] (list (byte-array ^"[B" header) (byte-array ^"[B" meta-size)  (byte-array ^"[B" data)))]
      (apply streamer data))))

(defn it-exists? 
  [db id]
  (some? (rdb/get db id)))
  
(defn get-it 
  [db id]
  (split-header (rdb/get db id)))

(defn delete-it 
  [db id]
  (rdb/delete db id))

(defn update-it 
  [db id data]
  (rdb/put db id data))
  
(defn get-keys 
  [db]
  (map #(split-header (second %)) (rdb/iterator db)))

(defn raw-get
  [db id]
  (rdb/get db id))
  
(defn raw-update
  [db id data]
  (rdb/put db id data))