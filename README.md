# JobMatch Engine — Full-Stack

Full-stack job-search automation: parse a résumé, pull jobs from many live sources,
ATS-score them against the résumé, tailor a one-page LaTeX résumé, and track applications.

## Tech stack

**Frontend** (`/frontend`)
- **Next.js 14** (App Router) + **React 18**
- **TypeScript**
- **Tailwind CSS** (custom theme, `@apply` component layer)
- Talks to the backend via Next.js `rewrites` proxy (`/api/* → :8080`) — no CORS

**Backend** (`/src`)
- **Java 17+** / **Spring Boot 3.3** (Spring MVC REST, embedded Tomcat)
- **Maven** build (fat jar)
- **Jackson** (JSON), **Apache PDFBox** (PDF parsing), **Apache POI** (DOCX parsing)
- **Java HttpClient** (HTTP/1.1) with concurrent (`ExecutorService`) multi-source pulls
- **SQLite/JDBC** tracker persistence (`data/jobmatch.db`)
- **Kafka** tracker event publishing when enabled (`jobmatch.tracker.events`)

## Features
- **Resume parsing** — PDF / DOCX / TXT / TEX → structured `Profile`
- **Job search (Path A)** — Greenhouse · Lever · Ashby (259 verified sponsor boards) ·
  Workday · SmartRecruiters · The Muse · Adzuna · Job-API fallback (SerpApi → JSearch →
  Careerjet → Jooble) · Remote APIs (Remotive + RemoteOK) · Discovery (Google PSE / Brave /
  Bing / Tavily / SerpApi web search across LinkedIn, Indeed, Dice, startup & H1B/OPT boards)
- **Job search (Path B)** — generates a CO-STAR AI-search prompt → paste the JSON back to import
- **ATS scoring** — weighted résumé-vs-JD match, batch-scored & ranked
- **Tailor** — deterministic one-page LaTeX résumé + keyword-coverage match
- **H1B sponsor lookup** — curated 342-company DB with confidence
- **Application tracker** — saved/applied status pipeline, SQLite persistence, optional Kafka events

## Run it

### Local dev

```bash
./run.sh          # builds + starts API (:8080) and UI (:3000), opens the browser
```

Or run the two processes manually:

```bash
# 1) Backend (port 8080)
mvn -DskipTests package
java -jar target/jobmatch-engine-0.1.0.jar

# 2) Frontend (port 3000, proxies /api → 8080)
cd frontend
npm install
npm run dev      # open http://localhost:3000
```

API keys for the keyed sources (Adzuna, SerpApi, JSearch, Careerjet, Jooble, Brave, Bing,
Google PSE, Tavily) are read from a local `.env` (git-ignored).

### Docker + Kafka

```bash
docker compose up --build
```

Services:

- App UI: http://localhost:3000
- Spring Boot API: http://localhost:8080
- Kafka UI: http://localhost:8085
- Kafka external listener: `localhost:9094`

The backend stores tracker data in `./data/jobmatch.db` and publishes tracker
events to Kafka topic `jobmatch.tracker.events` when run through Compose.
Docker Compose automatically reads a local `.env` for variable substitution, so
existing job-source API keys can be reused inside the backend container.

### Deploy a resume demo

For a public resume link, deploy the Spring Boot Docker service. The backend serves
the static UI at `/` and the API at `/api/*`, so one hosted URL is enough for a demo.
Kafka is disabled in the hosted demo unless you attach a managed Kafka service.

Recommended options:

- **Render**: create a new Blueprint from this GitHub repo. `render.yaml` builds
  `Dockerfile`, exposes the app, and checks `/api/tracker`.
- **Railway**: create a new project from this GitHub repo. `railway.toml` builds
  the same Dockerfile and uses `/api/tracker` as the health check.

Add API keys from `.env.example` in the hosting dashboard if you want live aggregator
sources such as Adzuna, SerpApi, JSearch, Careerjet, Jooble, Brave, Bing, Google PSE,
or Tavily to work in production.

> Note: AI résumé rewriting, cover letters, and LLM fit-scoring require an LLM API key and
> are intentionally not wired (no key configured).
