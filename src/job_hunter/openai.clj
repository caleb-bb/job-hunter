(ns job-hunter.openai
  "OpenAI API integration for job suitability analysis and cover letter generation.
   Uses gpt-4o-mini by default for cost efficiency."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private api-url "https://api.openai.com/v1/chat/completions")

(defn- get-api-key []
  (or (System/getenv "OPENAI_API_KEY")
      (throw (ex-info "OPENAI_API_KEY environment variable not set" {}))))

(defn- chat-completion
  "Call OpenAI chat completions API. Returns the assistant message text."
  [model messages]
  (let [api-key  (get-api-key)
        response (http/post api-url
                            {:headers      {"Authorization" (str "Bearer " api-key)
                                            "Content-Type"  "application/json"}
                             :body         (json/generate-string
                                             {:model       model
                                              :messages    messages
                                              :temperature 0.3
                                              :max_tokens  2000})
                             :as           :json
                             :throw-exceptions false})
        status   (:status response)
        body     (:body response)]
    (if (= 200 status)
      (get-in body [:choices 0 :message :content])
      (do (log/error "OpenAI API error — status:" status "body:" body)
          nil))))

(defn- truncate-text
  "Truncate posting text to avoid blowing up context windows.
   ~3000 words ≈ ~4000 tokens, well within limits."
  [text max-words]
  (let [words (str/split text #"\s+")]
    (if (<= (count words) max-words)
      text
      (str (str/join " " (take max-words words)) "\n\n[...truncated]"))))

;; ---------------------------------------------------------------------------
;; Location Filter
;; ---------------------------------------------------------------------------

(defn- strip-code-fences
  "Remove markdown code fences (```json ... ```) that LLMs love to add."
  [s]
  (-> s
      str/trim
      (str/replace #"^```(?:json)?\s*" "")
      (str/replace #"\s*```$" "")
      str/trim))

(defn- classify-location-batch
  "Classify a batch of posting titles as US-available or not.
   Returns a vector of booleans (true = US, false = not)."
  [titles model]
  (let [numbered (str/join "\n" (map-indexed #(str (inc %1) ". " %2) titles))
        prompt   (str "Classify each job posting by whether it is available to a worker "
                      "in the United States. Respond with ONLY a JSON array of booleans.\n\n"
                      "true  = available to US workers\n"
                      "false = clearly restricted to outside the US\n\n"
                      "Rules:\n"
                      "- US city/state/region → true\n"
                      "- \"Remote\" with no country restriction → true\n"
                      "- \"Remote (US)\" or \"Remote US\" → true\n"
                      "- Non-US country/city with no US option → false\n"
                      "- \"Remote Europe\", \"Remote UK\", \"Remote Australia\" → false\n"
                      "- Mixed locations that include at least one US location → true\n"
                      "- \"LATAM\" or \"Latin America\" only → false\n\n"
                      "Postings:\n" numbered "\n\n"
                      "Respond with ONLY a JSON array like [true, false, true, ...]. "
                      "No other text. Array length must be exactly " (count titles) ".")
        result   (chat-completion model [{:role "user" :content prompt}])]
    (when result
      (try
        (let [parsed (json/parse-string (strip-code-fences result))]
          (if (and (sequential? parsed) (= (count parsed) (count titles)))
            (vec parsed)
            (do (log/warn "Location batch: wrong length — expected" (count titles) "got" (count parsed))
                (vec (repeat (count titles) true)))))
        (catch Exception _
          (log/warn "Failed to parse location classification:" result)
          (vec (repeat (count titles) true)))))))

(defn filter-us-postings
  "Filter postings to only those available to US-based workers.
   Uses LLM to classify locations from posting titles in batches."
  [postings model]
  (let [batch-size 25
        batches    (partition-all batch-size postings)
        n-batches  (count (seq batches))]
    (log/info "Location filter: classifying" (count postings) "postings in" n-batches "batches")
    (vec
      (mapcat
        (fn [batch]
          (let [titles  (mapv :title batch)
                flags   (or (classify-location-batch titles model)
                            (vec (repeat (count batch) true)))
                results (keep-indexed (fn [i posting] (when (nth flags i true) posting)) batch)
                dropped (- (count batch) (count results))]
            (when (pos? dropped)
              (log/info "  Dropped" dropped "non-US postings"))
            results))
        batches))))

;; ---------------------------------------------------------------------------
;; Suitability Analysis
;; ---------------------------------------------------------------------------

(defn analyze-suitability
  "Ask OpenAI whether a posting is suitable given resume + goals.
   Returns the posting map with :suitable? boolean and :suitability-reasoning string,
   or nil on API failure."
  [{:keys [title text url] :as posting} resume goals model]
  (log/info "Analyzing suitability:" title)
  (let [prompt (str "You are a career advisor. Evaluate whether this job posting "
                    "is a good match for the candidate based on their resume and goals.\n\n"
                    "## Job Posting\n"
                    "Title: " title "\n"
                    "URL: " url "\n\n"
                    (truncate-text text 3000)
                    "\n\n## Candidate Resume\n"
                    (truncate-text resume 2000)
                    "\n\n## Candidate Goals\n"
                    goals
                    "\n\n## Instructions\n"
                    "Respond in EXACTLY this format:\n"
                    "SUITABLE: YES or NO\n"
                    "REASONING: 2-3 sentences explaining why.\n"
                    "KEY_MATCHES: comma-separated list of matching skills/requirements\n"
                    "GAPS: comma-separated list of missing requirements (if any)")
        result (chat-completion model [{:role "user" :content prompt}])]
    (if result
      (let [suitable? (boolean (re-find #"(?i)SUITABLE:\s*YES" result))]
        (log/info "  →" (if suitable? "SUITABLE ✓" "NOT SUITABLE ✗") title)
        (assoc posting
               :suitable? suitable?
               :suitability-reasoning result))
      (do
        (log/warn "  → API call failed for:" title)
        nil))))

;; ---------------------------------------------------------------------------
;; Cover Letter Drafting
;; ---------------------------------------------------------------------------

(defn draft-cover-letter
  "Ask OpenAI to draft a cover letter for a suitable posting.
   Returns the cover letter text, or nil on failure."
  [{:keys [title text url suitability-reasoning]} resume goals model]
  (log/info "Drafting cover letter for:" title)
  (let [prompt (str "You are a professional career writer. Draft a concise, compelling "
                    "cover letter for this job posting. The letter should:\n"
                    "- Be 3-4 paragraphs\n"
                    "- Open with genuine interest in the specific role/company\n"
                    "- Highlight 2-3 specific experiences from the resume that match the posting\n"
                    "- Reference the candidate's goals where they align with the role\n"
                    "- Sound human and authentic, not generic or sycophantic\n"
                    "- Close with a clear call to action\n\n"
                    "- Contain no emdashes \n\n"
                    "## Job Posting\n"
                    "Title: " title "\n"
                    "URL: " url "\n\n"
                    (truncate-text text 2000)
                    "\n\n## Prior Analysis\n"
                    (or suitability-reasoning "")
                    "\n\n## Candidate Resume\n"
                    (truncate-text resume 2000)
                    "\n\n## Candidate Goals\n"
                    goals
                    "\n\nWrite the cover letter now. Do not include placeholders like "
                    "[Your Name] — just write the body text.")
        result (chat-completion model [{:role "user" :content prompt}])]
    (if result
      (do (log/info "  → Cover letter drafted (" (count (str/split-lines result)) "lines)")
          result)
      (do (log/warn "  → Cover letter generation failed for:" title)
          nil))))
