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
