(ns job-hunter.tracker
  "Tracks which posting URLs have already been processed.
   Persists as an EDN set in applied.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

(def ^:private default-path "applied.edn")

(defn load-applied
  "Read the set of already-processed URLs from applied.edn.
   Returns #{} if file missing or unreadable."
  ([] (load-applied default-path))
  ([path]
   (try
     (if (.exists (io/file path))
       (let [data (edn/read-string (slurp path))]
         (if (set? data)
           (do (log/info "Loaded" (count data) "previously processed URLs from" path)
               data)
           (do (log/warn path "did not contain a set — starting fresh")
               #{})))
       (do (log/info path "not found — starting fresh")
           #{}))
     (catch Exception ex
       (log/error ex "Failed to read" path "— starting fresh")
       #{}))))

(defn save-applied!
  "Write the updated set of processed URLs to applied.edn."
  ([applied-set] (save-applied! applied-set default-path))
  ([applied-set path]
   (try
     (spit path (pr-str applied-set))
     (log/info "Saved" (count applied-set) "processed URLs to" path)
     (catch Exception ex
       (log/error ex "Failed to write" path)))))

(defn filter-new
  "Remove postings whose :url is already in the applied set."
  [applied-set postings]
  (let [result (remove #(contains? applied-set (:url %)) postings)]
    (log/info "Tracker:" (count postings) "→" (count (vec result))
              "new postings (" (- (count postings) (count (vec result))) "already seen)")
    ;; Re-realize since we logged count — avoid double-traversal issues
    (vec result)))
