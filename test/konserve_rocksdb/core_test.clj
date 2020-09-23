(ns konserve-rocksdb.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :refer [<!!] :as async]
            [konserve.core :as k]
            [konserve.storage-layout :as kl]
            [konserve-rocksdb.core :refer [new-rocksdb-store delete-store]]
            [malli.generator :as mg]
            [clojure.java.io :as io])
  (:import  [java.io File]))

(deftype UnknownType [])

(defn exception? [thing]
  (instance? Throwable thing))
        
(defn delete-recursively [fname]
  (let [func (fn [func f]
               (when (.isDirectory ^File f)
                 (doseq [^File f2 (.listFiles ^File f)]
                   (func func f2)))
               (try (io/delete-file f) (catch Exception _ nil)))]
    (func func (io/file fname))))

(defn my-test-fixture [f]
  (.mkdirs (java.io.File. "./temp"))
  (f)
  (delete-recursively "./temp"))

(use-fixtures :once my-test-fixture)

(deftest get-nil-test
  (testing "Test getting on empty store"
    (let [_ (println "Getting from an empty store")
          path "./temp/nil-test"
          store (<!! (new-rocksdb-store path))]
      (is (= nil (<!! (k/get store :foo))))
      (is (= nil (<!! (k/get-meta store :foo))))
      (is (not (<!! (k/exists? store :foo))))
      (is (= :default (<!! (k/get-in store [:fuu] :default))))
      (<!! (k/bget store :foo (fn [res] 
                                (is (nil? res))))))))

(deftest write-value-test
  (testing "Test writing to store"
    (let [_ (println "Writing to store")
          path "./temp/write-test"
          store (<!! (new-rocksdb-store path))]
      (is (not (<!! (k/exists? store :foo))))
      (<!! (k/assoc store :foo :bar))
      (is (<!! (k/exists? store :foo)))
      (is (= :bar (<!! (k/get store :foo))))
      (is (= :foo (:key (<!! (k/get-meta store :foo)))))
      (<!! (k/assoc-in store [:baz] {:bar 42}))
      (is (= 42 (<!! (k/get-in store [:baz :bar]))))
      (delete-store store))))

(deftest update-value-test
  (testing "Test updating values in the store"
    (let [_ (println "Updating values in the store")
          path "./temp/update-test"
          store (<!! (new-rocksdb-store path))]
      (<!! (k/assoc store :foo :baritone))
      (is (= :baritone (<!! (k/get-in store [:foo]))))
      (<!! (k/update-in store [:foo] name))
      (is (= "baritone" (<!! (k/get-in store [:foo]))))
      (delete-store store))))

(deftest exists-test
  (testing "Test check for existing key in the store"
    (let [_ (println "Checking if keys exist")
          path "./temp/exists-test"
          store (<!! (new-rocksdb-store path))]
      (is (not (<!! (k/exists? store :foo))))
      (<!! (k/assoc store :foo :baritone))
      (is  (<!! (k/exists? store :foo)))
      (<!! (k/dissoc store :foo))
      (is (not (<!! (k/exists? store :foo))))
      (delete-store store))))

(deftest binary-test
  (testing "Test writing binary date"
    (let [_ (println "Reading and writing binary data")
          path "./temp/binary-test"
          store (<!! (new-rocksdb-store path))
          cb (atom false)
          cb2 (atom false)]
      (is (not (<!! (k/exists? store :binbar))))
      (<!! (k/bget store :binbar (fn [ans] (is (nil? (:input-stream ans))))))
      (<!! (k/bassoc store :binbar (byte-array (range 30))))
      (<!! (k/bget store :binbar (fn [res]
                                    (reset! cb true)
                                    (is (= (map byte (slurp (:input-stream res)))
                                           (range 30))))))
      (<!! (k/bassoc store :binbar (byte-array (map inc (range 30))))) 
      (<!! (k/bget store :binbar (fn [res]
                                    (reset! cb2 true)
                                    (is (= (map byte (slurp (:input-stream res)))
                                           (map inc (range 30)))))))                                          
      (is (<!! (k/exists? store :binbar)))
      (is @cb)
      (is @cb2)
      (delete-store store))))
  
(deftest key-test
  (testing "Test getting keys from the store"
    (let [_ (println "Getting keys from store")
          path "./temp/key-test"
          store (<!! (new-rocksdb-store path))]
      (is (= #{} (<!! (async/into #{} (k/keys store)))))
      (<!! (k/assoc store :baz 20))
      (<!! (k/assoc store :binbar 20))
      (is (= #{:baz :binbar} (<!! (async/into #{} (k/keys store)))))
      (delete-store store))))  

(deftest append-test
  (testing "Test the append store functionality."
    (let [_ (println "Appending to store")
          path "./temp/append-test"
          store (<!! (new-rocksdb-store path))]
      (<!! (k/append store :foo {:bar 42}))
      (<!! (k/append store :foo {:bar 43}))
      (is (= (<!! (k/log store :foo))
             '({:bar 42}{:bar 43})))
      (is (= (<!! (k/reduce-log store
                              :foo
                              (fn [acc elem]
                                (conj acc elem))
                              []))
             [{:bar 42} {:bar 43}]))
      (delete-store store))))

(deftest invalid-store-test
  (testing "Invalid store functionality."
    (let [_ (println "pathecting to invalid store")
          store (<!! (new-rocksdb-store (UnknownType.)))]
      (is (exception? store)))))


(def home
  [:map
    [:name string?]
    [:description string?]
    [:rooms pos-int?]
    [:capacity float?]
    [:address
      [:map
        [:street string?]
        [:number int?]
        [:country [:enum "kenya" "lesotho" "south-africa" "italy" "mozambique" "spain" "india" "brazil" "usa" "germany"]]]]])

(deftest realistic-test
  (testing "Realistic data test."
    (let [_ (println "Entering realistic data")
          path "./temp/realistic-test"
          store (<!! (new-rocksdb-store path))
          home (mg/generate home {:size 20 :seed 2})
          address (:address home)
          addressless (dissoc home :address)
          name (mg/generate keyword? {:size 15 :seed 3})
          num1 (mg/generate pos-int? {:size 5 :seed 4})
          num2 (mg/generate pos-int? {:size 5 :seed 5})
          floater (mg/generate float? {:size 5 :seed 6})]
      
      (<!! (k/assoc store name addressless))
      (is (= addressless 
             (<!! (k/get store name))))

      (<!! (k/assoc-in store [name :address] address))
      (is (= home 
             (<!! (k/get store name))))

      (<!! (k/update-in store [name :capacity] * floater))
      (is (= (* floater (:capacity home)) 
             (<!! (k/get-in store [name :capacity]))))  

      (<!! (k/update-in store [name :address :number] + num1 num2))
      (is (= (+ num1 num2 (:number address)) 
             (<!! (k/get-in store [name :address :number]))))             
      
      (delete-store store))))   

(deftest bulk-test
  (testing "Bulk data test."
    (let [_ (println "Writing bulk data")
          path "./temp/bulk-test"
          store (<!! (new-rocksdb-store path))
          string20MB (apply str (repeat 20971520 "7"))
          range2MB 2097152
          sevens (repeat range2MB 7)]
      (print "\nWriting 20MB string: ")
      (time (<!! (k/assoc store :record string20MB)))
      (is (= (count string20MB) (count (<!! (k/get store :record)))))
      (print "Writing 2MB binary: ")
      (time (<!! (k/bassoc store :binary (byte-array sevens))))
      (<!! (k/bget store :binary (fn [{:keys [input-stream]}]
                                    (is (= (pmap byte (slurp input-stream))
                                           sevens)))))
      (delete-store store))))  

(deftest raw-test
  (testing "Test header and value storage"
    (let [_ (println "Checking if headers and values are stored correctly")
          path "./temp/raw-test"
          store (<!! (new-rocksdb-store path))]
      (<!! (k/assoc store :foo :bar))
      (<!! (k/assoc store :eye :ear))
      (let [raw (<!! (kl/-get-raw store :foo))
            raw2 (<!! (kl/-get-raw store :eye))
            raw3 (<!! (kl/-get-raw store :not-there))
            header (take 4 (map byte raw))]
        (<!! (kl/-put-raw store :foo raw2))
        (<!! (kl/-put-raw store :baritone raw2))
        (is (= header [0 1 1 0]))
        (is (nil? raw3))
        (is (= :ear (<!! (k/get store :baritone))))
        (is (= (<!! (k/get store :foo)) (<!! (k/get store :baritone))))
        (is (= (<!! (k/get-meta store :foo)) (<!! (k/get-meta store :baritone)))))
      (delete-store store))))          

(deftest exceptions-test
  (testing "Test exception handling"
    (let [_ (println "Generating exceptions")
          path "./temp/exceptions-test"
          store (<!! (new-rocksdb-store path))
          params (clojure.core/keys store)
          corruptor (fn [s k] 
                        (if (= (type (k s)) clojure.lang.Atom)
                          (clojure.core/assoc-in s [k] (atom {})) 
                          (clojure.core/assoc-in s [k] (UnknownType.))))
          corrupt (reduce corruptor store params)] ; let's corrupt our store
      (is (exception? (<!! (k/get corrupt :bad))))
      (is (exception? (<!! (k/get-meta corrupt :bad))))
      (is (exception? (<!! (k/assoc corrupt :bad 10))))
      (is (exception? (<!! (k/dissoc corrupt :bad))))
      (is (exception? (<!! (k/assoc-in corrupt [:bad :robot] 10))))
      (is (exception? (<!! (k/update-in corrupt [:bad :robot] inc))))
      (is (exception? (<!! (k/exists? corrupt :bad))))
      (is (exception? (<!! (k/keys corrupt))))
      (is (exception? (<!! (k/bget corrupt :bad (fn [_] nil)))))   
      (is (exception? (<!! (k/bassoc corrupt :binbar (byte-array (range 10))))))
      (is (exception? (<!! (kl/-put-raw corrupt :bad (byte-array (range 10))))))
      (is (exception? (<!! (kl/-get-raw corrupt :bad))))
      (is (exception? (<!! (delete-store corrupt))))
      (delete-store store))))