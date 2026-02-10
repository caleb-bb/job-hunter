(ns job-hunter.filter
  "Allowlist-based filtering of job postings.
   A posting passes if its title OR text contains at least one include-keyword."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defn matches-allowlist?
  "True if the posting's title or text contains at least one keyword (case-insensitive)."
  [keywords {:keys [title text]}]
  (let [haystack (str/lower-case (str title " " text))]
    (some #(str/includes? haystack (str/lower-case %)) keywords)))

(defn apply-allowlist
  "Filter postings to only those matching at least one include-keyword.
   Returns filtered seq. Logs counts."
  [keywords postings]
  (if (seq keywords)
    (let [result (filter (partial matches-allowlist? keywords) postings)]
      (log/info "Allowlist filter:" (count postings) "→" (count result)
                "postings (keywords:" (str/join ", " keywords) ")")
      result)
    (do
      (log/info "No include-keywords configured — passing all" (count postings) "postings through")
      postings)))
