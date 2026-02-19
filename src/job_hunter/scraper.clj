(ns job-hunter.scraper
  "Site-specific scraping via multimethod dispatch on :type.
   Each method returns a seq of posting maps:
   {:url string, :title string, :text string, :source keyword}"
  (:require [etaoin.api :as e]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- safe-text
  "Extract text from an element, returning empty string on failure."
  [driver el]
  (try (e/get-element-text-el driver el)
       (catch Exception _ "")))

(defn- safe-attr
  "Extract attribute from an element, returning nil on failure."
  [driver el attr]
  (try (e/get-element-attr-el driver el attr)
       (catch Exception _ nil)))

(defn- polite-pause
  "Sleep for delay-ms to avoid hammering servers."
  [delay-ms]
  (when (pos? delay-ms)
    (Thread/sleep delay-ms)))

(defn- resolve-url
  "If href is relative, prepend base-url."
  [href base-url]
  (cond
    (nil? href) nil
    (str/starts-with? href "http") href
    (str/starts-with? href "//") (str "https:" href)
    :else (str (str/replace base-url #"/$" "") href)))

(defn- truncate
  "Truncate text to max-len chars for titles."
  [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 max-len) "...")
    s))

(defn- strip-markdown
  "Remove basic markdown formatting (bold, italic, links) from text."
  [s]
  (-> s
      (str/replace #"\*\*([^*]+)\*\*" "$1")     ; **bold**
      (str/replace #"\*([^*]+)\*" "$1")          ; *italic*
      (str/replace #"\[([^\]]+)\]\([^)]+\)" "$1") ; [text](url)
      str/trim))

(defn- extract-title-from-text
  "Pull a reasonable title from the first line of posting text.
   HN Who's Hiring comments often start with 'Company | Role | Location'."
  [text]
  (let [first-line (first (str/split-lines text))]
    (truncate (strip-markdown (str/trim (or first-line "Untitled"))) 120)))

;; ---------------------------------------------------------------------------
;; Multimethod: dispatch on site :type
;; ---------------------------------------------------------------------------

(defmulti scrape-site
  "Given a browser driver and site config map, return a seq of posting maps."
  (fn [_driver site-config _opts] (:type site-config)))

(defmethod scrape-site :default [_ site-config _]
  (log/warn "No scraper for site type:" (:type site-config) "— skipping" (:id site-config))
  [])

;; ---------------------------------------------------------------------------
;; :hn — Hacker News "Who's Hiring" threads
;; ---------------------------------------------------------------------------
;; Structure: single page (paginated via "More" link) of comments.
;; Top-level comments (indent=0) are job postings.
;; Each comment lives in a tr.athing.comtr with:
;;   - td.ind img[width] indicating nesting depth (0 = top-level)
;;   - span.commtext containing the text
;;   - a with href "item?id=NNNNN" for the permalink

(defn- hn-extract-comments
  "Extract top-level comments from the currently loaded HN page.
   Uses JavaScript for reliable extraction — avoids e/child issues with
   HN's nested table structure and is much faster (1 call vs hundreds)."
  [driver]
  (let [raw (e/js-execute driver
              "var rows = document.querySelectorAll('tr.athing.comtr');
               var results = [];
               for (var i = 0; i < rows.length; i++) {
                 var tr = rows[i];
                 var ind = tr.querySelector('td.ind');
                 if (ind && ind.getAttribute('indent') === '0') {
                   var textEl = tr.querySelector('.commtext');
                   var ageLink = tr.querySelector('span.age a');
                   if (textEl) {
                     results.push({
                       text: textEl.innerText || '',
                       href: ageLink ? ageLink.getAttribute('href') : null
                     });
                   }
                 }
               }
               return results;")]
    (log/debug "JS extracted" (count raw) "top-level comments from page")
    (reduce
      (fn [acc {:keys [text href]}]
        (if (str/blank? text)
          acc
          (let [post-url (when href
                           (if (str/starts-with? href "http")
                             href
                             (str "https://news.ycombinator.com/" href)))]
            (conj acc {:url    (or post-url (str "hn-comment-" (count acc)))
                       :title  (extract-title-from-text text)
                       :text   text
                       :source :hn-who-is-hiring}))))
      []
      raw)))

(defn- hn-load-more
  "Click the 'More' link if present. Returns true if more pages loaded."
  [driver]
  (try
    (let [more-link (e/query driver {:css "a.morelink"})]
      (when more-link
        (e/click-el driver more-link)
        (e/wait-visible driver {:css "tr.athing.comtr"})
        true))
    (catch Exception _ false)))

(defmethod scrape-site :hn [driver {:keys [url name]} {:keys [request-delay-ms max-postings-per-site]}]
  (log/info "Scraping" name "—" url)
  (e/go driver url)
  (e/wait-visible driver {:css "tr.athing.comtr"})
  (loop [all-comments []
         page 1]
    (let [page-comments (hn-extract-comments driver)
          combined      (into all-comments page-comments)]
      (log/info "  Page" page ":" (count page-comments) "postings (" (count combined) "total)")
      (if (and (< (count combined) max-postings-per-site)
               (< page 5))  ;; safety: max 5 pages
        (do
          (polite-pause request-delay-ms)
          (if (hn-load-more driver)
            (recur combined (inc page))
            combined))
        combined))))

;; ---------------------------------------------------------------------------
;; :discourse — Discourse forums (Elixir Forum, etc.)
;; ---------------------------------------------------------------------------
;; Topic list page has links in tr.topic-list-item > td.main-link a.title
;; Topic pages have post content in .cooked class

(defmethod scrape-site :discourse [driver {:keys [url name]} {:keys [request-delay-ms max-postings-per-site]}]
  (log/info "Scraping" name "—" url)
  (e/go driver url)
  (polite-pause 2000)  ;; Discourse can be slow to render
  (let [topic-links (try (e/query-all driver {:css "a.title.raw-link"})
                         (catch Exception _
                           (try (e/query-all driver {:css "a.title"})
                                (catch Exception _ []))))
        link-data   (reduce
                      (fn [acc el]
                        (let [href  (safe-attr driver el :href)
                              title (safe-text driver el)]
                          (if (and href (not (str/blank? title)))
                            (conj acc {:href (resolve-url href url)
                                       :title (str/trim title)})
                            acc)))
                      []
                      (take max-postings-per-site topic-links))]
    (log/info "  Found" (count link-data) "topic links")
    (reduce
      (fn [postings {:keys [href title]}]
        (polite-pause request-delay-ms)
        (log/info "  Loading:" title)
        (try
          (e/go driver href)
          (polite-pause 1500)
          (let [content-els (e/query-all driver {:css ".cooked"})
                text        (str/join "\n\n" (map #(safe-text driver %) content-els))]
            (conj postings {:url    href
                            :title  title
                            :text   (if (str/blank? text) title text)
                            :source :discourse}))
          (catch Exception ex
            (log/warn "  Failed to load topic:" title (.getMessage ex))
            postings)))
      []
      link-data)))

;; ---------------------------------------------------------------------------
;; :reddit — Reddit subreddits via old.reddit.com
;; ---------------------------------------------------------------------------
;; old.reddit.com structure:
;;   div.thing[data-url] contains each post
;;   a.title has the post title + href
;;   Self posts have the text on the post page in div.usertext-body .md

(defmethod scrape-site :reddit [driver {:keys [url name]} {:keys [request-delay-ms max-postings-per-site]}]
  (log/info "Scraping" name "—" url)
  (let [old-url (str/replace url "www.reddit.com" "old.reddit.com")]
    (e/go driver old-url)
    (polite-pause 2000)
    (let [post-links (try (e/query-all driver {:css "#siteTable div.thing a.title"})
                          (catch Exception _
                            (try (e/query-all driver {:css "a.title"})
                                 (catch Exception _ []))))
          link-data  (reduce
                       (fn [acc el]
                         (let [href  (safe-attr driver el :href)
                               title (safe-text driver el)]
                           (if (and href (not (str/blank? title)))
                             (conj acc {:href (resolve-url href "https://old.reddit.com")
                                        :title (str/trim title)})
                             acc)))
                       []
                       (take max-postings-per-site post-links))]
      (log/info "  Found" (count link-data) "posts")
      (reduce
        (fn [postings {:keys [href title]}]
          (polite-pause request-delay-ms)
          (log/info "  Loading:" title)
          (try
            (e/go driver href)
            (polite-pause 1500)
            (let [body-els (e/query-all driver {:css ".usertext-body .md"})
                  text     (str/join "\n\n" (map #(safe-text driver %) body-els))]
              (conj postings {:url    href
                              :title  title
                              :text   (if (str/blank? text) title text)
                              :source :reddit}))
            (catch Exception ex
              (log/warn "  Failed to load post:" title (.getMessage ex))
              postings)))
        []
        link-data))))

;; ---------------------------------------------------------------------------
;; :reddit-hiring — Reddit "Who's Hiring" threads (r/rust, etc.)
;; ---------------------------------------------------------------------------
;; Config keys:
;;   :url            — subreddit base URL (e.g. https://old.reddit.com/r/rust)
;;   :hiring-pattern — substring to match in thread titles (default "who's hiring")
;; Uses Reddit's JSON API (not browser) to avoid being blocked:
;;   1. Search the subreddit for threads matching :hiring-pattern
;;   2. Fetch top-level comments from the newest matching thread
;; Comments without an external URL get the Reddit comment permalink instead.

(def ^:private reddit-user-agent "job-hunter/1.0 (clojure)")

(defn- normalize-quotes
  "Replace Unicode smart quotes/apostrophes with ASCII equivalents."
  [s]
  (-> s
      (str/replace "\u2018" "'")  ; left single
      (str/replace "\u2019" "'")  ; right single
      (str/replace "\u201C" "\"") ; left double
      (str/replace "\u201D" "\""))) ; right double

(defn- reddit-json-search
  "Search a subreddit via JSON API for the first thread matching `pattern`.
   Returns the thread's permalink path (e.g. /r/rust/comments/xxx/...) or nil."
  [subreddit-url pattern]
  (try
    (let [search-url (str subreddit-url "/search.json")
          resp       (http/get search-url
                       {:query-params {"q"           (str "\"" pattern "\"")
                                       "restrict_sr" "on"
                                       "sort"        "new"
                                       "t"           "year"}
                        :headers      {"User-Agent" reddit-user-agent}
                        :as           :json
                        :throw-exceptions false})
          posts      (get-in resp [:body :data :children])
          lc-pattern (str/lower-case pattern)]
      (when (= 200 (:status resp))
        (->> posts
             (filter #(str/includes?
                        (-> (get-in % [:data :title] "")
                            str/lower-case
                            normalize-quotes)
                        lc-pattern))
             first
             :data
             :permalink)))
    (catch Exception e
      (log/warn "Reddit JSON search failed:" (.getMessage e))
      nil)))

(defn- reddit-json-comments
  "Fetch top-level comments from a Reddit thread via JSON API.
   Returns a seq of maps with :body (markdown text), :permalink, :author."
  [permalink]
  (try
    (let [url  (str "https://old.reddit.com" permalink ".json")
          resp (http/get url
                 {:query-params {"limit" "500" "depth" "1" "sort" "new"}
                  :headers      {"User-Agent" reddit-user-agent}
                  :as           :json
                  :throw-exceptions false})]
      (when (= 200 (:status resp))
        (let [listing  (second (:body resp))
              children (get-in listing [:data :children])]
          (->> children
               (filter #(= "t1" (:kind %)))
               (map :data)
               (map (fn [d] {:body      (:body d)
                             :permalink (str "https://old.reddit.com" (:permalink d))
                             :author    (:author d)}))))))
    (catch Exception e
      (log/warn "Reddit JSON comments failed:" (.getMessage e))
      nil)))

(defn- extract-url-from-markdown
  "Find the first external (non-reddit) URL in markdown text."
  [text]
  (let [urls (re-seq #"https?://[^\s\)\]>\"']+" text)]
    (first (remove #(str/includes? % "reddit.com") urls))))

(defmethod scrape-site :reddit-hiring
  [_driver {:keys [url name hiring-pattern]
            :or {hiring-pattern "who's hiring"}}
   {:keys [max-postings-per-site]}]
  (log/info "Scraping" name "via JSON API —" url)
  ;; Step 1: Search for the latest hiring thread
  (let [permalink (reddit-json-search url hiring-pattern)]
    (if-not permalink
      (do (log/warn "  No '" hiring-pattern "' thread found on" name)
          [])
      (do
        (log/info "  Found hiring thread:" permalink)
        ;; Step 2: Fetch top-level comments
        (let [comments (reddit-json-comments permalink)]
          (log/info "  Found" (count comments) "top-level comments")
          (into []
                (take max-postings-per-site)
                (keep (fn [{:keys [body permalink author]}]
                        (when-not (or (str/blank? body)
                                      ;; Skip mod meta-comments
                                      (str/starts-with? body "This is the top-level comment for"))
                          (let [ext-url (extract-url-from-markdown body)]
                            {:url    (or ext-url permalink)
                             :title  (extract-title-from-text body)
                             :text   body
                             :source :reddit-hiring})))
                      comments)))))))

;; ---------------------------------------------------------------------------
;; :css — Generic CSS-selector-based scraping
;; ---------------------------------------------------------------------------
;; Config keys:
;;   :listing-selector  — CSS for links on the listing page
;;   :link-attr         — attribute to read for URL (default :href)
;;   :content-selector  — CSS for content on the detail page
;;   :base-url          — prepend to relative links

(defmethod scrape-site :css [driver {:keys [url name listing-selector link-attr
                                            content-selector base-url]
                                     :or {link-attr :href}}
                              {:keys [request-delay-ms max-postings-per-site]}]
  (log/info "Scraping" name "—" url)
  (e/go driver url)
  (polite-pause 2000)
  (let [link-els  (try (e/query-all driver {:css listing-selector})
                       (catch Exception _ []))
        link-data (reduce
                    (fn [acc el]
                      (let [href  (safe-attr driver el link-attr)
                            title (safe-text driver el)]
                        (if href
                          (conj acc {:href  (resolve-url href (or base-url url))
                                     :title (if (str/blank? title) href (str/trim title))})
                          acc)))
                    []
                    (take max-postings-per-site link-els))]
    (log/info "  Found" (count link-data) "links via" listing-selector)
    (if content-selector
      ;; Visit each link and extract content
      (reduce
        (fn [postings {:keys [href title]}]
          (polite-pause request-delay-ms)
          (log/info "  Loading:" title)
          (try
            (e/go driver href)
            (polite-pause 1500)
            (let [content-els (e/query-all driver {:css content-selector})
                  text        (str/join "\n\n" (map #(safe-text driver %) content-els))]
              (conj postings {:url    href
                              :title  title
                              :text   (if (str/blank? text) title text)
                              :source (:id (meta link-data))}))
            (catch Exception ex
              (log/warn "  Failed to load:" title (.getMessage ex))
              postings)))
        []
        link-data)
      ;; No content selector — listing titles are the postings themselves
      (mapv (fn [{:keys [href title]}]
              {:url href :title title :text title :source :css})
            link-data))))

;; ---------------------------------------------------------------------------
;; :twitter — Stub
;; ---------------------------------------------------------------------------

(defmethod scrape-site :twitter [_ {:keys [name]} _]
  (log/warn "Twitter scraping not implemented." name
            "Use Twitter API v2 or Nitter. Skipping.")
  [])

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- fetch-last-modified
  "HEAD request to grab the Last-Modified header. Returns the value or nil."
  [url]
  (try
    (let [resp (http/head url {:throw-exceptions false
                               :socket-timeout   5000
                               :connection-timeout 5000
                               :redirect-strategy :lax})]
      (get-in resp [:headers "Last-Modified"]))
    (catch Exception e
      (log/debug "HEAD request failed for" url (.getMessage e))
      nil)))

(defn scrape-all-sites
  "Scrape all configured sites.
   Returns {:postings [posting-map …]
            :last-modified {site-id last-modified-value-or-nil …}}
   driver — etaoin browser driver
   sites  — seq of site config maps from config.edn
   opts   — {:request-delay-ms N, :max-postings-per-site N}"
  [driver sites opts]
  (let [results (mapv (fn [site]
                        (let [postings (try
                                         (scrape-site driver site opts)
                                         (catch Exception ex
                                           (log/error ex "Failed to scrape site:" (:id site))
                                           []))
                              lm       (fetch-last-modified (:url site))]
                          (when lm
                            (log/info "  Last-Modified for" (:name site) ":" lm))
                          {:postings postings
                           :site-id  (:id site)
                           :last-modified lm}))
                      sites)]
    {:postings      (into [] (mapcat :postings) results)
     :last-modified (into {} (map (juxt :site-id :last-modified)) results)}))
