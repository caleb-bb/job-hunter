(ns job-hunter.writer
  "Writes suitable postings + cover letters as markdown files to output/."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(def ^:private output-dir "output")

(defn- sanitize-filename
  "Convert a title into a safe filename. Lowercase, hyphens, truncated."
  [title]
  (-> title
      str/lower-case
      (str/replace #"[^a-z0-9\s-]" "")
      str/trim
      (str/replace #"\s+" "-")
      (str/replace #"-+" "-")
      (#(if (> (count %) 80) (subs % 0 80) %))
      (#(str/replace % #"-$" ""))))

(defn- timestamp []
  (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")))

(defn write-posting!
  "Write a single posting + cover letter to output/{title}.md"
  [{:keys [url title text source suitability-reasoning]} cover-letter]
  (.mkdirs (io/file output-dir))
  (let [filename (str (sanitize-filename title) ".md")
        filepath (str output-dir "/" filename)
        ;; Handle filename collision
        filepath (if (.exists (io/file filepath))
                   (str output-dir "/" (sanitize-filename title) "-" (System/currentTimeMillis) ".md")
                   filepath)
        content  (str "# " title "\n\n"
                      "**URL:** " url "  \n"
                      "**Source:** " (name (or source :unknown)) "  \n"
                      "**Processed:** " (timestamp) "\n\n"
                      "---\n\n"
                      "## Suitability Analysis\n\n"
                      (or suitability-reasoning "_No analysis available._") "\n\n"
                      "---\n\n"
                      "## Cover Letter Draft\n\n"
                      (or cover-letter "_No cover letter generated._") "\n\n"
                      "---\n\n"
                      "## Original Posting\n\n"
                      text "\n")]
    (spit filepath content)
    (log/info "Wrote:" filepath)
    filepath))

(defn write-summary!
  "Write a summary of all processed postings to output/_summary.md"
  [suitable-postings all-new-count total-scraped]
  (.mkdirs (io/file output-dir))
  (let [filepath (str output-dir "/_summary.md")
        content  (str "# Job Hunt Summary — " (timestamp) "\n\n"
                      "**Total scraped:** " total-scraped "  \n"
                      "**After allowlist filter:** " all-new-count "  \n"
                      "**Suitable matches:** " (count suitable-postings) "\n\n"
                      (if (seq suitable-postings)
                        (str "## Suitable Postings\n\n"
                             (str/join "\n"
                                       (map-indexed
                                         (fn [i {:keys [title url]}]
                                           (str (inc i) ". [" title "](" url ")"))
                                         suitable-postings)))
                        "No suitable postings found this run.")
                      "\n")]
    (spit filepath content)
    (log/info "Wrote summary:" filepath)
    filepath))
