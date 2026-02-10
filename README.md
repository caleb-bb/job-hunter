# job-hunter

Clojure pipeline that scrapes job boards, filters by keywords, checks suitability via OpenAI, and drafts cover letters.

## Prerequisites

- **Java 21+** (for Clojure 1.12)
- **Clojure CLI** (`brew install clojure/tools/clojure`)
- **Chrome + ChromeDriver** — etaoin drives a real browser
  ```bash
  # macOS
  brew install --cask chromedriver
  # If blocked by Gatekeeper:
  xattr -d com.apple.quarantine $(which chromedriver)
  ```
- **OpenAI API key** with credits

## Setup

```bash
# 1. Set your API key
export OPENAI_API_KEY="sk-..."

# 2. Edit resume.md — paste your full resume
# 3. Edit goals.md — describe what you want from a job
# 4. Edit config.edn:
#    - Update the HN Who's Hiring thread URL (changes monthly)
#    - Tune include-keywords for your search
#    - Add/remove sites as needed

# 5. Install deps
cd job-hunter
clj -P
```

## Usage

```bash
# Dry run — scrape + filter only, no OpenAI calls, no cost
clj -M:run-dry

# Full run — scrape → filter → analyze → write cover letters
clj -M:run

# REPL (recommended for iterating)
clj
# then: (require '[job-hunter.core :as jh])
#        (jh/run-pipeline! :dry-run? true)
```

## Output

- `output/*.md` — one file per suitable posting (analysis + cover letter + original text)
- `output/_summary.md` — run summary with links
- `applied.edn` — auto-updated set of processed URLs (prevents re-processing)

## Adding Sites

Edit `config.edn`. Each site needs `:id`, `:type`, `:url`, and `:name`.

**Supported types:**

| Type | For | Config keys |
|------|-----|-------------|
| `:hn` | HN Who's Hiring threads | Just `:url` |
| `:discourse` | Discourse forums (Elixir Forum) | Just `:url` |
| `:reddit` | Reddit subreddits | `:url` (auto-converts to old.reddit.com) |
| `:css` | Any site with consistent HTML | `:listing-selector`, `:content-selector`, `:base-url` |
| `:twitter` | Stub — not functional | Use Twitter API v2 instead |

**Adding a generic site example:**
```clojure
{:id               :my-board
 :type             :css
 :url              "https://example.com/jobs"
 :name             "My Board"
 :listing-selector "a.job-title"
 :content-selector ".job-description"
 :base-url         "https://example.com"}
```

## Architecture

```
URLs → etaoin scrapes → keyword allowlist filter → applied.edn dedup
     → OpenAI suitability analysis (pass/fail)
     → OpenAI cover letter draft
     → markdown files in output/
```

Scraper uses multimethod dispatch on `:type` — add new site types by implementing `(defmethod scraper/scrape-site :your-type ...)`.

## Costs

With `gpt-4o-mini` (default): ~$0.01-0.03 per posting analyzed (suitability + cover letter). A run processing 20 postings costs roughly $0.30-0.60. Switch to `gpt-4o` in config for higher quality at ~10× cost.

## Troubleshooting

- **ChromeDriver version mismatch**: ChromeDriver version must match your Chrome version. `brew upgrade chromedriver` or download from https://googlechromelabs.github.io/chrome-for-testing/
- **Scraper finds 0 postings**: CSS selectors change. Run with `:headless? false` in config to watch the browser, inspect the DOM, and update selectors.
- **Rate limited by a site**: Increase `:request-delay-ms` in config.
- **OpenAI errors**: Check that `OPENAI_API_KEY` is set and has credits.
