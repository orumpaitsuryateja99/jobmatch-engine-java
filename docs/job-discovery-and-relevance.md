# Job Discovery, Freshness & Relevance

How JobMatch Engine finds jobs, filters them, scores them, and builds the
recommended résumé. All filtering described here is enforced **in the Spring Boot
backend**, not only in the browser.

## Job sources

| Source | Auth | Posting time available? | Notes |
|---|---|---|---|
| Greenhouse boards | none | yes (`updated_at`) | core sponsor boards (`data/` catalog) |
| Lever boards | none | yes (`createdAt`) | core sponsor boards |
| Ashby boards | none | yes (`publishedAt`) | core sponsor boards |
| The Muse | none | yes (`publication_date`) | optional |
| Workday CXS | none | yes (`postedOn`) | heavier per-board detail fetch |
| SmartRecruiters | none | yes (`releasedDate`) | |
| Adzuna | `ADZUNA_APP_ID` + `ADZUNA_APP_KEY` | yes (`created`) | aggregator |
| Job-API fallback | one of `SERPAPI_API_KEY` → `JSEARCH/RAPIDAPI` → `CAREERJET_AFFID` → `JOOBLE_API_KEY` | yes | tries each in order |
| Remote APIs (Remotive, RemoteOK) | none | yes (epoch) | remote-only |
| Discovery web search | one of `GOOGLE_API_KEY`+`GOOGLE_CX` / `BRAVE_API_KEY` / `BING_API_KEY` / `TAVILY_API_KEY` / `SERPAPI_API_KEY` | **no per-result date** | freshness applied provider-side (see below) |

### API keys / `.env`

Keyed sources read from a git-ignored `.env` at the project root (loaded at
startup). Missing keys disable that source; the Find Jobs panel shows ✅ (ready)
or 🔒 (no key). The "sources status" endpoint (`/api/jobs/sources/status`) drives
those badges.

## Discovery websites

Discovery runs a web search (LinkedIn, Indeed, Dice, startup & H1B/OPT boards,
etc.) via whichever provider has a key, then turns the result links into jobs.

- **All On** selects every discovery site; **All Off** deselects all. Individual
  checkboxes still work.
- Discovery only runs when a search provider key is present (else the section is 🔒).

## Freshness ("Posted within") — strict

Selecting a window (24h / 3d / 7d / 14d / 30d) sets `maxAgeHours`. Filtering is
**strict** and happens at fetch time against each posting's **original**
timestamp via `PostedDate.tooOld(ts, maxAgeHours)`:

- A posting is kept only if its parsed age ≤ `maxAgeHours`.
- **Unknown posting time + a window selected ⇒ excluded.** `tooOld` returns
  `true` when the date can't be parsed and a window is active. So "posted within
  24 hours" never shows undated or older-than-24h jobs.
- `PostedDate` parses relative phrases ("3 days ago", "today", "yesterday"),
  ISO dates, `Mon DD, YYYY`, and epoch seconds/millis.

Source-specific notes:
- Greenhouse/Lever/Ashby/Muse/Workday/SmartRecruiters/Adzuna/JSearch/Careerjet/
  Jooble/Remotive/RemoteOK and **SerpApi Google Jobs** (`detected_extensions.
  posted_at`) all run `tooOld` per result.
- **Discovery** leads have no per-result date, so freshness is applied
  **provider-side**: Google PSE `dateRestrict`, Brave `freshness`, Tavily
  `time_range`, SerpApi web `tbs=qdr:*`. With "Any time" there is no restriction.

To verify the 24h filter: pick "Past 24 hours", run a board search, and confirm
every card's posted date is today/yesterday; undated sources contribute nothing.

## Title / focus filtering — exclusion, not just ranking

Role focus (New Grad SWE, Backend, …) maps to `Roles.titleMatchesFocus(title,
focusKeys)`. A posting whose **title** doesn't match the selected focus is
**dropped** (not merely ranked lower), and `Roles.HARD_REJECT` titles
(QA, support, sales, non-SWE engineering, PM, …) are excluded unless they're a
generic SWE title. `newGradOnly` additionally drops senior titles and JDs whose
required years exceed `maxYears`.

## Relevance / ATS scoring

After fetching, jobs are scored against your résumé with the same formula as the
Python reference (`AtsScorer`):

```
ATS = 0.33·hard_skill + 0.27·keyword + 0.15·title + 0.10·tools
    + 0.05·experience + 0.05·domain + 0.05·formatting       (each 0–100)
```

- **hard_skill** — % of JD skills present in your résumé skills.
- **keyword** — % of tech JD keywords present in your résumé keyword pool
  (every content word you wrote + canonical skill/tool tokens).
- **title** — token overlap between the JD title and your target roles; senior
  titles are penalised ×0.3.
- **tools / experience / domain / formatting** — supporting signals.

Bands: ≥85 Strong · ≥70 Good · ≥55 Stretch · else Weak. Match & Score sorts by
this score (or company/source). Because off-title and stale jobs are already
**excluded** upstream, the ranked list only contains relevant, fresh postings.

## Recommended résumé (deterministic, no API key)

`/api/resume/tailor/auto` (`AutoTailorService`) builds a tailored résumé from
your parsed résumé + the selected JD, **without inventing anything**:

- detects role angle (ML/AI · Backend/API · Full-stack · Frontend);
- reorders skill categories + items JD-first;
- re-ranks each section's real bullets toward the JD and lightly polishes them;
- orders projects by angle; drops the ML/CV-only project for non-ML roles;
- writes a JD-targeted summary from facts the résumé proves;
- returns ATS score, matched skills, and missing/recommended skills.

The PDF (`/api/resume/pdf`, compiled with tectonic/pdflatex) and `.tex`
(`/api/resume/template`) downloads correspond exactly to this tailored résumé —
not the raw upload and not the Claude prompt. Missing JD skills are surfaced in
the UI as "recommended to add" and are **never** written into the résumé.

## Known limitations

- Discovery freshness is provider-granular (day/week/month), not exact hours.
- Boards without a parseable timestamp are excluded under any active window.
- `.tex` master résumés that use pdftex-only macros (`\input{glyphtounicode}`,
  `\pdfgentounicode`) won't compile with tectonic; the app's **generated** résumé
  uses portable LaTeX and compiles cleanly.
