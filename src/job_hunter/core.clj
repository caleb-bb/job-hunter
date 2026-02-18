(ns job-hunter.core
  "Pipeline orchestration: scrape → filter → deduplicate → analyze → write.
   Entry point: -main or (run!) from REPL."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [etaoin.api :as e]
            [job-hunter.scraper :as scraper]
            [job-hunter.filter :as filter]
            [job-hunter.tracker :as tracker]
            [job-hunter.openai :as openai]
            [job-hunter.writer :as writer])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Site state (last-modified timestamps, persisted separately from config)
;; ---------------------------------------------------------------------------

(def ^:private site-state-path "site-state.edn")

(defn- load-site-state
  "Load {site-id last-modified-string} from site-state.edn."
  []
  (try
    (if (.exists (io/file site-state-path))
      (let [data (edn/read-string (slurp site-state-path))]
        (if (map? data) data {}))
      {})
    (catch Exception _ {})))

(defn- save-site-state!
  "Persist {site-id last-modified-string} to site-state.edn."
  [state]
  (spit site-state-path (pr-str state))
  (log/info "Saved site state for" (count state) "sites"))

(defn- merge-site-state
  "Merge persisted :last-modified values into site config maps."
  [sites state]
  (mapv (fn [site]
          (assoc site :last-modified (get state (:id site))))
        sites))

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

(defn load-config
  "Read config.edn from project root. Merges persisted site state
   (last-modified timestamps) into each site map."
  ([] (load-config "config.edn"))
  ([path]
   (let [config (edn/read-string (slurp path))
         state  (load-site-state)
         config (update config :sites merge-site-state state)]
     (log/info "Loaded config:" (count (:sites config)) "sites,"
               (count (:include-keywords config)) "keywords")
     config)))

(defn- load-text-file
  "Slurp a file, returning nil with a warning if missing."
  [path description]
  (let [f (java.io.File. path)]
    (if (.exists f)
      (let [text (str/trim (slurp f))]
        (if (str/blank? text)
          (do (log/warn description "is empty:" path)
              nil)
          (do (log/info "Loaded" description "(" (count (str/split-lines text)) "lines)")
              text)))
      (do (log/warn description "not found:" path)
          nil))))

;; ---------------------------------------------------------------------------
;; Browser lifecycle
;; ---------------------------------------------------------------------------

(defn start-browser! [headless?]
  (log/info "Starting Chrome" (if headless? "(headless)" "(visible)"))
  (if headless?
    (e/chrome-headless)
    (e/chrome)))

(defn stop-browser! [driver]
  (when driver
    (try (e/quit driver)
         (log/info "Browser closed.")
         (catch Exception ex
           (log/warn "Error closing browser:" (.getMessage ex))))))

;; ---------------------------------------------------------------------------
;; Pipeline
;; ---------------------------------------------------------------------------

(defn run-pipeline!
  "Execute the full scrape → filter → analyze → write pipeline.
   Options:
     :dry-run?  — scrape and filter only, skip OpenAI calls (default false)"
  [& {:keys [dry-run?] :or {dry-run? false}}]
  (let [config  (load-config)
        resume  (load-text-file "resume.md" "Resume")
        goals   (load-text-file "goals.md" "Goals")
        _       (when (and (not dry-run?) (or (nil? resume) (nil? goals)))
                  (log/warn "⚠ Resume or goals missing — OpenAI analysis will be poor quality"))
        applied (tracker/load-applied)
        opts    {:request-delay-ms     (or (:request-delay-ms config) 2000)
                 :max-postings-per-site (or (:max-postings-per-site config) 50)}
        driver  (start-browser! (get config :headless? true))]
    (try
      ;; Step 1: Scrape all sites
      (log/info "═══ STEP 1: Scraping ═══")
      (let [{all-postings :postings last-mod :last-modified}
                         (scraper/scrape-all-sites driver (:sites config) opts)
            _            (save-site-state! (merge (load-site-state) last-mod))
            _            (log/info "Total postings scraped:" (count all-postings))

            ;; Step 2: Allowlist filter
            _            (log/info "═══ STEP 2: Filtering ═══")
            filtered     (filter/apply-allowlist (:include-keywords config) all-postings)

            ;; Step 2b: Location filter (LLM-based)
            filtered     (if (:location-filter config)
                           (do (log/info "═══ STEP 2b: Location filter ═══")
                               (openai/filter-us-postings
                                 filtered
                                 (or (:openai-model config) "gpt-4o-mini")))
                           filtered)

            ;; Step 3: Deduplicate against applied.edn
            _            (log/info "═══ STEP 3: Deduplicating ═══")
            new-postings (tracker/filter-new applied filtered)
            _            (log/info "New postings to process:" (count new-postings))]

        (if dry-run?
          ;; Dry run: just print what we found
          (do
            (log/info "═══ DRY RUN — skipping OpenAI ═══")
            (doseq [{:keys [title url source]} new-postings]
              (println (str "  [" (name (or source :unknown)) "] " title))
              (println (str "    " url)))
            (println (str "\n" (count new-postings) " postings would be analyzed.")))

          ;; Full run: analyze + write
          (do
            ;; Step 4: Suitability analysis
            (log/info "═══ STEP 4: Analyzing suitability ═══")
            (let [model     (or (:openai-model config) "gpt-4o-mini")
                  analyzed  (keep #(openai/analyze-suitability % (or resume "") (or goals "") model)
                                  new-postings)
                  suitable  (filter :suitable? analyzed)
                  _         (log/info "Suitable postings:" (count suitable) "of" (count analyzed) "analyzed")]

              ;; Step 5: Draft cover letters + write files
              (log/info "═══ STEP 5: Writing cover letters ═══")
              (let [output-files
                    (doall
                      (for [posting suitable]
                        (let [cover-letter (openai/draft-cover-letter
                                            posting (or resume "") (or goals "") model)]
                          (writer/write-posting! posting cover-letter))))]

                ;; Write summary
                (writer/write-summary! suitable (count new-postings) (count all-postings))

                ;; Update tracker — mark ALL new postings as seen (not just suitable ones)
                (tracker/save-applied!
                  (into applied (map :url) new-postings))

                (log/info "═══ DONE ═══")
                (println (str "\nResults:"
                              "\n  Scraped:    " (count all-postings)
                              "\n  Filtered:   " (count filtered)
                              "\n  New:        " (count new-postings)
                              "\n  Suitable:   " (count suitable)
                              "\n  Files:      " (count output-files)
                              "\n  Output dir: output/"))))))

        ;; Return stats for REPL use
        {:scraped  (count all-postings)
         :filtered (count filtered)
         :new      (count new-postings)})

      (finally
        (stop-browser! driver)))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (let [dry-run? (some #{"--dry-run"} args)]
    (try
      (run-pipeline! :dry-run? (boolean dry-run?))
      (System/exit 0)
      (catch Exception ex
        (log/error ex "Pipeline failed")
        (println (str "ERROR: " (.getMessage ex)))
        (System/exit 1)))))

;; ---------------------------------------------------------------------------
;; REPL helpers
;; ---------------------------------------------------------------------------

(comment
  ;; Full run
  (run-pipeline!)

  ;; Dry run — scrape + filter only, no OpenAI
  (run-pipeline! :dry-run? true)

  ;; Quick test: just load config
  (load-config)

  ;; Check what's in applied.edn
  (tracker/load-applied)

  ;; Reset tracker (re-process everything)
  (tracker/save-applied! #{})
  )
