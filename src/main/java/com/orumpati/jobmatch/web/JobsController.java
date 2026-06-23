package com.orumpati.jobmatch.web;

import com.orumpati.jobmatch.jobs.DiscoveryService;
import com.orumpati.jobmatch.jobs.JobApiClient;
import com.orumpati.jobmatch.jobs.JobBoardClient;
import com.orumpati.jobmatch.jobs.SourceCatalogService;
import com.orumpati.jobmatch.jobs.SourceCatalogService.Board;
import com.orumpati.jobmatch.model.JobPosting;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Mirrors Tab 2 ("Find Jobs") board pulls from app/sources.py — Greenhouse,
 * Lever, Ashby, and The Muse, all public/no-key. */
@RestController
@RequestMapping("/api/jobs")
public class JobsController {

    private final JobBoardClient jobBoardClient;
    private final SourceCatalogService catalog;
    private final JobApiClient jobApiClient;
    private final DiscoveryService discovery;

    public JobsController(JobBoardClient jobBoardClient, SourceCatalogService catalog,
                          JobApiClient jobApiClient, DiscoveryService discovery) {
        this.jobBoardClient = jobBoardClient;
        this.catalog = catalog;
        this.jobApiClient = jobApiClient;
        this.discovery = discovery;
    }

    /** Which aggregator providers are configured (key present) — drives the Find Jobs
     * source panel ✅/🔒 badges, same as the Python app's quota/availability checks. */
    @GetMapping("/sources/status")
    public Map<String, Object> sourcesStatus() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("adzuna", jobApiClient.adzunaReady());
        m.put("serpapi", jobApiClient.serpapiReady());
        m.put("jsearch", jobApiClient.jsearchReady());
        m.put("careerjet", jobApiClient.careerjetReady());
        m.put("jooble", jobApiClient.joobleReady());
        m.put("jobApiFallback", jobApiClient.jobApiReady());
        m.put("remoteApis", true);   // Remotive + RemoteOK are no-key
        m.put("workday", true);
        m.put("smartrecruiters", true);
        m.put("discovery", discovery.available());
        m.put("discoveryProvider", discovery.provider());
        return m;
    }

    /** Discovery site labels + the auto-detected search provider. */
    @GetMapping("/discovery/labels")
    public Map<String, Object> discoveryLabels() {
        return Map.of("labels", discovery.labels(), "provider", discovery.provider(),
                "available", discovery.available());
    }

    /** Discovery search — web-search across the selected site labels (Google PSE /
     * Brave / Bing / Tavily / SerpApi), normalized + entry-level/focus gated. */
    @PostMapping("/discovery")
    public List<JobPosting> discoverySearch(@RequestBody DiscoveryRequest req) {
        int n = req.maxResults() != null && req.maxResults() > 0 ? req.maxResults() : 10;
        List<JobPosting> jobs = discovery.search(req.siteLabels(), req.newGradOnly(),
                req.maxYears(), req.focusKeys(), req.maxAgeHours(), n);
        jobs.removeIf(j -> j.getError() != null);
        return jobBoardClient.dedupe(jobs);
    }

    /** Path A — keyword aggregator search across the keyed/no-key APIs (Adzuna,
     * Job-API fallback SerpApi→JSearch→Careerjet→Jooble, Remote APIs), cross-source
     * deduped. The selected sources run concurrently. */
    @PostMapping("/api-search")
    public List<JobPosting> apiSearch(@RequestBody ApiSearchRequest req) {
        String q = req.query() == null || req.query().isBlank() ? "software engineer new grad" : req.query();
        boolean ng = req.newGradOnly();
        int my = req.maxYears();
        Double age = req.maxAgeHours();
        List<String> srcs = req.sources() == null ? List.of() : req.sources();
        List<String> fk = req.focusKeys();

        List<Callable<List<JobPosting>>> tasks = new ArrayList<>();
        if (srcs.contains("adzuna"))    tasks.add(() -> jobApiClient.adzuna(q, ng, my, fk, age));
        if (srcs.contains("jobApi"))    tasks.add(() -> jobApiClient.jobApiFallback(q, ng, my, fk, age));
        if (srcs.contains("remote"))  { tasks.add(() -> jobApiClient.remotive(ng, my, fk, age));
                                        tasks.add(() -> jobApiClient.remoteok(ng, my, fk, age)); }

        List<JobPosting> all = new ArrayList<>();
        if (!tasks.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, tasks.size()));
            try {
                for (Future<List<JobPosting>> f : pool.invokeAll(tasks)) {
                    try { all.addAll(f.get()); } catch (Exception ignore) {}
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { pool.shutdown(); }
        }
        all.removeIf(j -> j.getError() != null);
        return jobBoardClient.dedupe(all);
    }

    /** Catalog counts for the Find Jobs source panel ("N sponsor boards"). */
    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        return Map.of(
                "total", catalog.boards().size(),
                "greenhouse", catalog.count("greenhouse"),
                "lever", catalog.count("lever"),
                "ashby", catalog.count("ashby"),
                "boards", catalog.boards());
    }

    /** Path A — pulls the verified sponsor boards from target_companies.txt CONCURRENTLY
     * (capped by `limit`, default 60, for responsiveness), filters, and cross-source
     * dedupes. The Python aggregator parallelizes ~200 boards with a 15-min cache; this
     * is the no-key core of that. */
    @PostMapping("/sponsor-search")
    public List<JobPosting> sponsorSearch(@RequestBody SponsorSearchRequest req) {
        int limit = req.limit() != null && req.limit() > 0 ? req.limit() : 40;
        // ATS-board pulls: gh/lever/ashby capped by `limit`; Workday/SmartRecruiters
        // (heavier CXS detail fetches) only when their toggles are on.
        List<Board> ghLeverAshby = catalog.boards().stream()
                .filter(b -> b.ats().equals("greenhouse") || b.ats().equals("lever") || b.ats().equals("ashby"))
                .toList();
        if (ghLeverAshby.size() > limit) ghLeverAshby = ghLeverAshby.subList(0, limit);
        List<Board> boards = new ArrayList<>(ghLeverAshby);
        if (req.includeWorkday())
            catalog.boards().stream().filter(b -> b.ats().equals("workday")).forEach(boards::add);
        if (req.includeSmartRecruiters())
            catalog.boards().stream().filter(b -> b.ats().equals("smartrecruiters")).forEach(boards::add);

        List<String> focusKeys = req.focusKeys();
        List<Callable<List<JobPosting>>> tasks = new ArrayList<>();
        for (Board b : boards) {
            final Board bd = b;
            tasks.add(() -> switch (bd.ats()) {
                case "greenhouse" -> jobBoardClient.greenhouse(bd.token(), req.newGradOnly(), req.maxYears(), focusKeys, req.maxAgeHours(), bd.name());
                case "lever" -> jobBoardClient.lever(bd.token(), req.newGradOnly(), req.maxYears(), focusKeys, req.maxAgeHours(), bd.name());
                case "ashby" -> jobBoardClient.ashby(bd.token(), req.newGradOnly(), req.maxYears(), focusKeys, req.maxAgeHours(), bd.name());
                case "workday" -> jobApiClient.workday(bd.token(), req.newGradOnly(), req.maxYears(), focusKeys, req.maxAgeHours(), bd.name());
                case "smartrecruiters" -> jobApiClient.smartrecruiters(bd.token(), req.newGradOnly(), req.maxYears(), focusKeys, req.maxAgeHours(), bd.name());
                default -> List.of();
            });
        }

        List<JobPosting> all = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(24, Math.max(1, tasks.size())));
        try {
            for (Future<List<JobPosting>> f : pool.invokeAll(tasks)) {
                try {
                    all.addAll(f.get());
                } catch (Exception ignore) {
                    // one board failing must not sink the whole search
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }
        if (req.includeMuse()) {
            all.addAll(jobBoardClient.themuse(req.newGradOnly(), req.maxYears(), null, req.maxAgeHours(), 4));
        }
        // Drop the per-board error placeholders before returning the clean job list.
        all.removeIf(j -> j.getError() != null);
        return jobBoardClient.dedupe(all);
    }

    @GetMapping("/greenhouse/{boardToken}")
    public List<JobPosting> greenhouse(@PathVariable String boardToken,
                                        @RequestParam(defaultValue = "true") boolean newGradOnly,
                                        @RequestParam(defaultValue = "2") int maxYears,
                                        @RequestParam(required = false) Double maxAgeHours,
                                        @RequestParam(required = false) String displayName) {
        return jobBoardClient.dedupe(jobBoardClient.greenhouse(boardToken, newGradOnly, maxYears, null, maxAgeHours, displayName));
    }

    @GetMapping("/lever/{company}")
    public List<JobPosting> lever(@PathVariable String company,
                                   @RequestParam(defaultValue = "true") boolean newGradOnly,
                                   @RequestParam(defaultValue = "2") int maxYears,
                                   @RequestParam(required = false) Double maxAgeHours,
                                   @RequestParam(required = false) String displayName) {
        return jobBoardClient.dedupe(jobBoardClient.lever(company, newGradOnly, maxYears, null, maxAgeHours, displayName));
    }

    @GetMapping("/ashby/{company}")
    public List<JobPosting> ashby(@PathVariable String company,
                                   @RequestParam(defaultValue = "true") boolean newGradOnly,
                                   @RequestParam(defaultValue = "2") int maxYears,
                                   @RequestParam(required = false) Double maxAgeHours,
                                   @RequestParam(required = false) String displayName) {
        return jobBoardClient.dedupe(jobBoardClient.ashby(company, newGradOnly, maxYears, null, maxAgeHours, displayName));
    }

    @GetMapping("/themuse")
    public List<JobPosting> themuse(@RequestParam(defaultValue = "true") boolean newGradOnly,
                                     @RequestParam(defaultValue = "2") int maxYears,
                                     @RequestParam(required = false) Double maxAgeHours,
                                     @RequestParam(defaultValue = "6") int maxPages) {
        return jobBoardClient.dedupe(jobBoardClient.themuse(newGradOnly, maxYears, null, maxAgeHours, maxPages));
    }

    /** Pulls every configured Greenhouse/Lever/Ashby board token + The Muse in one
     * call and cross-source dedupes — same shape as the Python aggregator.search_all_sources
     * core path (without the paid SerpApi/Tavily/JSearch fallbacks). */
    @PostMapping("/pull")
    public List<JobPosting> pull(@RequestBody PullRequest req) {
        List<JobPosting> all = new ArrayList<>();
        for (String token : req.greenhouseBoards()) {
            all.addAll(jobBoardClient.greenhouse(token, req.newGradOnly(), req.maxYears(), null, req.maxAgeHours(), null));
        }
        for (String token : req.leverCompanies()) {
            all.addAll(jobBoardClient.lever(token, req.newGradOnly(), req.maxYears(), null, req.maxAgeHours(), null));
        }
        for (String token : req.ashbyCompanies()) {
            all.addAll(jobBoardClient.ashby(token, req.newGradOnly(), req.maxYears(), null, req.maxAgeHours(), null));
        }
        if (req.includeMuse()) {
            all.addAll(jobBoardClient.themuse(req.newGradOnly(), req.maxYears(), null, req.maxAgeHours(), 6));
        }
        return jobBoardClient.dedupe(all);
    }

    public record PullRequest(List<String> greenhouseBoards, List<String> leverCompanies,
                               List<String> ashbyCompanies, boolean includeMuse,
                               boolean newGradOnly, int maxYears, Double maxAgeHours) {}

    public record SponsorSearchRequest(boolean newGradOnly, int maxYears, Double maxAgeHours,
                                        boolean includeMuse, Integer limit,
                                        boolean includeWorkday, boolean includeSmartRecruiters,
                                        List<String> focusKeys) {}

    public record ApiSearchRequest(String query, List<String> sources, boolean newGradOnly,
                                    int maxYears, Double maxAgeHours, List<String> focusKeys) {}

    public record DiscoveryRequest(List<String> siteLabels, boolean newGradOnly, int maxYears,
                                    Double maxAgeHours, List<String> focusKeys, Integer maxResults) {}
}
