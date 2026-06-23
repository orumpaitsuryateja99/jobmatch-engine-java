package com.orumpati.jobmatch.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orumpati.jobmatch.config.ApiKeys;
import com.orumpati.jobmatch.model.JobPosting;
import com.orumpati.jobmatch.roles.Roles;
import com.orumpati.jobmatch.text.TextUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Java port of the keyed / no-key aggregator fetchers in app/sources.py:
 * Adzuna, JSearch, SerpApi Google Jobs, Careerjet, Jooble (keyed), plus Remotive
 * and RemoteOK (no key). Same entry-level + US gate and normalized JobPosting shape
 * as the ATS-board fetchers. Keys come from {@link ApiKeys} (.env / env vars). */
@Service
public class JobApiClient {

    private static final int MAX_YEARS = 2;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8)).build();
    private static final String UA = "JobMatchEngine/1.0 (personal job-search assistant)";
    private static final Pattern HTML_TAG_RE = Pattern.compile("<[^>]+>");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ApiKeys keys;

    public JobApiClient(ApiKeys keys) {
        this.keys = keys;
    }

    // ---------- shared helpers ----------
    private JsonNode getJson(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA).header("Accept", "application/json")
                .timeout(Duration.ofSeconds(12)).GET();
        if (headers != null) headers.forEach(b::header);
        HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode());
        return mapper.readTree(resp.body());
    }

    private JsonNode postJson(String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA).header("Content-Type", "application/json")
                .header("Accept", "application/json").timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode());
        return mapper.readTree(resp.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private String stripHtml(String s) {
        if (s == null) return "";
        String t = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ");
        t = HTML_TAG_RE.matcher(t).replaceAll(" ");
        t = t.replace("&amp;", "&");
        return t.strip().replaceAll("\\s+", " ");
    }

    private boolean entryLevelSwe(String title, String content, int maxYears, List<String> focusKeys) {
        if (!Roles.titleMatchesFocus(title, focusKeys)) return false;
        if (Roles.requiresNewGradSignal(focusKeys) && !Roles.isNewGradRole(title, content)) return false;
        if (TextUtils.isSeniorTitle(title)) return false;
        return TextUtils.extractYearsRequired(content) <= maxYears;
    }

    private boolean foreign(String text) {
        return "foreign".equals(TextUtils.detectUsLocation(text));
    }

    private JobPosting job(String title, String company, String loc, String link,
                           String source, String desc, String workMode, Object posted) {
        JobPosting j = new JobPosting();
        j.setTitle(title); j.setCompany(company); j.setLocation(loc);
        j.setJobLink(link == null ? "" : link); j.setSource(source); j.setDescription(desc);
        j.setWorkMode(workMode); j.setPostedDate(PostedDate.postedDateStr(posted));
        return j;
    }

    private JobPosting err(String m) { JobPosting j = new JobPosting(); j.setError(m); return j; }

    private String txt(JsonNode n, String field) { return n.path(field).asText(""); }

    // ---------- availability ----------
    public boolean adzunaReady()    { return keys.has("ADZUNA_APP_ID", "ADZUNA_APP_KEY"); }
    public boolean jsearchReady()   { return keys.hasAny("JSEARCH_API_KEY", "JSEARCH_RAPIDAPI_KEY"); }
    public boolean serpapiReady()   { return keys.hasAny("SERPAPI_API_KEY"); }
    public boolean careerjetReady() { return keys.hasAny("CAREERJET_AFFID"); }
    public boolean joobleReady()    { return keys.hasAny("JOOBLE_API_KEY"); }
    public boolean jobApiReady()    { return serpapiReady() || jsearchReady() || careerjetReady() || joobleReady(); }

    // ---------- ADZUNA ----------
    public List<JobPosting> adzuna(String query, boolean newGradOnly, int maxYears,
                                   List<String> focusKeys, Double maxAgeHours) {
        if (!adzunaReady()) return List.of(err("Adzuna: missing ADZUNA_APP_ID/ADZUNA_APP_KEY"));
        List<JobPosting> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            String url = "https://api.adzuna.com/v1/api/jobs/us/search/1?app_id=" + enc(keys.get("ADZUNA_APP_ID"))
                    + "&app_key=" + enc(keys.get("ADZUNA_APP_KEY")) + "&results_per_page=50&what=" + enc(query)
                    + "&content-type=application/json";
            for (JsonNode j : getJson(url, null).path("results")) {
                String title = txt(j, "title");
                String desc = stripHtml(txt(j, "description"));
                if (newGradOnly && !entryLevelSwe(title, desc, maxYears, focusKeys)) continue;
                String posted = j.path("created").asText(null);
                if (PostedDate.tooOld(posted, maxAgeHours)) continue;
                String loc = j.path("location").path("display_name").asText("");
                String link = txt(j, "redirect_url");
                String key = link.isBlank() ? (title + "|" + loc).toLowerCase() : link.toLowerCase();
                if (!seen.add(key)) continue;
                out.add(job(title, j.path("company").path("display_name").asText(""), loc, link,
                        "Adzuna", desc, TextUtils.detectWorkMode(loc + " " + desc), posted));
            }
        } catch (Exception e) { out.add(err("Adzuna: " + e.getMessage())); }
        return out;
    }

    // ---------- JSEARCH ----------
    public List<JobPosting> jsearch(String query, boolean newGradOnly, int maxYears,
                                    List<String> focusKeys, Double maxAgeHours) {
        boolean rapid = !keys.get("JSEARCH_API_KEY").isBlank() ? false : !keys.get("JSEARCH_RAPIDAPI_KEY").isBlank();
        String url; Map<String, String> headers = new HashMap<>();
        if (!keys.get("JSEARCH_API_KEY").isBlank()) {
            url = "https://api.openwebninja.com/jsearch/search-v2?query=" + enc(query) + "&country=us&language=en";
            headers.put("X-API-Key", keys.get("JSEARCH_API_KEY"));
        } else if (rapid) {
            url = "https://jsearch.p.rapidapi.com/search-v2?query=" + enc(query) + "&country=us&language=en";
            headers.put("X-RapidAPI-Key", keys.get("JSEARCH_RAPIDAPI_KEY"));
            headers.put("X-RapidAPI-Host", "jsearch.p.rapidapi.com");
        } else {
            return List.of(err("JSearch: missing JSEARCH_API_KEY / JSEARCH_RAPIDAPI_KEY"));
        }
        List<JobPosting> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            JsonNode payload = getJson(url, headers);
            JsonNode data = payload.path("data");
            JsonNode rows = data.isArray() ? data : (data.has("jobs") ? data.path("jobs")
                    : (payload.has("jobs") ? payload.path("jobs") : data.path("results")));
            for (JsonNode row : rows) {
                String title = row.has("job_title") ? txt(row, "job_title") : txt(row, "title");
                String desc = stripHtml(row.has("job_description") ? txt(row, "job_description") : txt(row, "description"));
                if (newGradOnly && !entryLevelSwe(title, desc, maxYears, focusKeys)) continue;
                String posted = row.path("job_posted_at_datetime_utc").asText(row.path("job_posted_at").asText(null));
                if (PostedDate.tooOld(posted, maxAgeHours)) continue;
                String cc = txt(row, "job_country").strip().toUpperCase();
                if (!cc.isEmpty() && !cc.equals("US") && !cc.equals("USA") && !cc.equals("UNITED STATES")) continue;
                String loc = row.has("job_location") ? txt(row, "job_location") : txt(row, "location");
                String link = jsearchApply(row);
                String key = link.isBlank() ? (txt(row, "employer_name") + "|" + title + "|" + loc).toLowerCase() : link.toLowerCase();
                if (!seen.add(key)) continue;
                out.add(job(title, row.has("employer_name") ? txt(row, "employer_name") : txt(row, "company_name"),
                        loc, link, "JSearch", desc, TextUtils.detectWorkMode(loc + " " + desc), posted));
            }
        } catch (Exception e) { out.add(err("JSearch: " + e.getMessage())); }
        return out;
    }

    private String jsearchApply(JsonNode row) {
        JsonNode opts = row.has("apply_options") ? row.path("apply_options") : row.path("job_apply_options");
        if (opts.isArray()) {
            for (JsonNode o : opts) if (o.path("is_direct").asBoolean(false)) {
                String l = o.has("apply_link") ? txt(o, "apply_link") : txt(o, "link");
                if (!l.isBlank()) return l;
            }
            for (JsonNode o : opts) {
                String l = o.has("apply_link") ? txt(o, "apply_link") : txt(o, "link");
                if (!l.isBlank()) return l;
            }
        }
        return row.has("job_apply_link") ? txt(row, "job_apply_link") : txt(row, "job_google_link");
    }

    // ---------- SERPAPI GOOGLE JOBS ----------
    public List<JobPosting> serpapi(String query, boolean newGradOnly, int maxYears,
                                    List<String> focusKeys, Double maxAgeHours) {
        if (!serpapiReady()) return List.of(err("SerpApi: missing SERPAPI_API_KEY"));
        List<JobPosting> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            String url = "https://serpapi.com/search.json?engine=google_jobs&api_key=" + enc(keys.get("SERPAPI_API_KEY"))
                    + "&q=" + enc(query) + "&location=" + enc("United States") + "&gl=us&hl=en&google_domain=google.com";
            JsonNode payload = getJson(url, null);
            if (payload.has("error")) return List.of(err("SerpApi: " + txt(payload, "error")));
            for (JsonNode row : payload.path("jobs_results")) {
                String title = txt(row, "title");
                String desc = stripHtml(txt(row, "description"));
                if (newGradOnly && !entryLevelSwe(title, desc, maxYears, focusKeys)) continue;
                String loc = txt(row, "location");
                if (foreign(title + " " + loc + " " + desc.substring(0, Math.min(500, desc.length())))) continue;
                // Strict freshness: google_jobs exposes "posted_at" ("3 days ago") in
                // detected_extensions. Honor the max-age filter and exclude undated rows.
                String posted = row.path("detected_extensions").path("posted_at").asText("");
                if (PostedDate.tooOld(posted, maxAgeHours)) continue;
                String link = serpapiApply(row);
                String key = (row.has("job_id") ? txt(row, "job_id") : link).toLowerCase();
                if (key.isBlank() || !seen.add(key)) continue;
                out.add(job(title, txt(row, "company_name"), loc, link, "SerpApi Google Jobs",
                        desc, TextUtils.detectWorkMode(loc + " " + desc), posted));
            }
        } catch (Exception e) { out.add(err("SerpApi: " + e.getMessage())); }
        return out;
    }

    private String serpapiApply(JsonNode row) {
        JsonNode opts = row.path("apply_options");
        if (opts.isArray()) for (JsonNode o : opts) {
            String l = txt(o, "link");
            if (!l.isBlank()) return l;
        }
        return row.path("share_link").asText("");
    }

    // ---------- CAREERJET ----------
    public List<JobPosting> careerjet(String query, boolean newGradOnly, int maxYears,
                                      List<String> focusKeys, Double maxAgeHours) {
        if (!careerjetReady()) return List.of(err("Careerjet: missing CAREERJET_AFFID"));
        List<JobPosting> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            String url = "http://public.api.careerjet.net/search?keywords=" + enc(query)
                    + "&location=" + enc("United States") + "&affid=" + enc(keys.get("CAREERJET_AFFID"))
                    + "&user_ip=11.22.33.44&user_agent=" + enc(UA) + "&pagesize=50&page=1&contenttype=application/json";
            // Careerjet rejects calls (HTTP 403 "Undeclared referrer") without a Referer header.
            for (JsonNode row : getJson(url, Map.of("Referer", "https://www.careerjet.com/")).path("jobs")) {
                String title = txt(row, "title");
                String desc = stripHtml(txt(row, "description"));
                if (newGradOnly && !entryLevelSwe(title, desc, maxYears, focusKeys)) continue;
                String posted = row.path("date").asText(null);
                if (PostedDate.tooOld(posted, maxAgeHours)) continue;
                String loc = txt(row, "locations");
                if (foreign(loc + " " + desc.substring(0, Math.min(400, desc.length())))) continue;
                String link = txt(row, "url");
                String key = link.isBlank() ? (txt(row, "company") + "|" + title + "|" + loc).toLowerCase() : link.toLowerCase();
                if (!seen.add(key)) continue;
                out.add(job(title, txt(row, "company"), loc, link, "Careerjet", desc,
                        TextUtils.detectWorkMode(loc + " " + desc), posted));
            }
        } catch (Exception e) { out.add(err("Careerjet: " + e.getMessage())); }
        return out;
    }

    // ---------- JOOBLE ----------
    public List<JobPosting> jooble(String query, boolean newGradOnly, int maxYears,
                                   List<String> focusKeys, Double maxAgeHours) {
        if (!joobleReady()) return List.of(err("Jooble: missing JOOBLE_API_KEY"));
        List<JobPosting> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            String body = mapper.writeValueAsString(Map.of("keywords", query, "location", "United States", "page", 1));
            for (JsonNode row : postJson("https://jooble.org/api/" + keys.get("JOOBLE_API_KEY"), body).path("jobs")) {
                String title = txt(row, "title");
                String desc = stripHtml(row.has("snippet") ? txt(row, "snippet") : txt(row, "description"));
                if (newGradOnly && !entryLevelSwe(title, desc, maxYears, focusKeys)) continue;
                String posted = row.path("updated").asText(null);
                if (PostedDate.tooOld(posted, maxAgeHours)) continue;
                String loc = txt(row, "location");
                if (foreign(loc + " " + desc.substring(0, Math.min(400, desc.length())))) continue;
                String link = txt(row, "link");
                String key = link.isBlank() ? (txt(row, "company") + "|" + title + "|" + loc).toLowerCase() : link.toLowerCase();
                if (!seen.add(key)) continue;
                out.add(job(title, txt(row, "company"), loc, link, "Jooble", desc,
                        TextUtils.detectWorkMode(loc + " " + desc), posted));
            }
        } catch (Exception e) { out.add(err("Jooble: " + e.getMessage())); }
        return out;
    }

    /** Job-API fallback chain — tries one provider per run in priority order
     * (SerpApi → JSearch → Careerjet → Jooble), first with results wins. Mirrors
     * aggregator.job_api_fallback() in the Python app. */
    public List<JobPosting> jobApiFallback(String query, boolean newGradOnly, int maxYears,
                                           List<String> focusKeys, Double maxAgeHours) {
        List<List<JobPosting>> tries = new ArrayList<>();
        if (serpapiReady())   tries.add(serpapi(query, newGradOnly, maxYears, focusKeys, maxAgeHours));
        if (jsearchReady())   tries.add(jsearch(query, newGradOnly, maxYears, focusKeys, maxAgeHours));
        if (careerjetReady()) tries.add(careerjet(query, newGradOnly, maxYears, focusKeys, maxAgeHours));
        if (joobleReady())    tries.add(jooble(query, newGradOnly, maxYears, focusKeys, maxAgeHours));
        for (List<JobPosting> t : tries) {
            List<JobPosting> clean = t.stream().filter(j -> j.getError() == null).toList();
            if (!clean.isEmpty()) return clean;
        }
        return tries.isEmpty() ? List.of(err("Job API: no provider configured")) : tries.get(0);
    }

    // ---------- REMOTIVE (no key) ----------
    public List<JobPosting> remotive(boolean newGradOnly, int maxYears, List<String> focusKeys, Double maxAgeHours) {
        List<JobPosting> out = new ArrayList<>();
        try {
            for (JsonNode j : getJson("https://remotive.com/api/remote-jobs?category=software-dev", null).path("jobs")) {
                String title = txt(j, "title");
                String desc = stripHtml(txt(j, "description"));
                if (newGradOnly && !entryLevelSwe(title, desc, maxYears, focusKeys)) continue;
                String posted = j.path("publication_date").asText(null);
                if (PostedDate.tooOld(posted, maxAgeHours)) continue;
                String loc = j.path("candidate_required_location").asText("Remote");
                if (loc.isBlank()) loc = "Remote";
                if (foreign(loc)) continue;
                out.add(job(title, txt(j, "company_name"), loc, txt(j, "url"), "Remotive", desc, "Remote", posted));
            }
        } catch (Exception e) { out.add(err("Remotive: " + e.getMessage())); }
        return out;
    }

    // ---------- REMOTEOK (no key) ----------
    public List<JobPosting> remoteok(boolean newGradOnly, int maxYears, List<String> focusKeys, Double maxAgeHours) {
        List<JobPosting> out = new ArrayList<>();
        try {
            for (JsonNode j : getJson("https://remoteok.com/api", null)) {
                if (!j.has("position") || txt(j, "position").isBlank()) continue;
                String title = txt(j, "position");
                String desc = stripHtml(txt(j, "description"));
                if (newGradOnly && !entryLevelSwe(title, desc, maxYears, focusKeys)) continue;
                String posted = j.path("date").asText(null);
                if (PostedDate.tooOld(posted, maxAgeHours)) continue;
                String loc = j.path("location").asText("Remote");
                if (loc.isBlank()) loc = "Remote";
                if (foreign(loc)) continue;
                String link = j.has("url") ? txt(j, "url") : txt(j, "apply_url");
                out.add(job(title, txt(j, "company"), loc, link, "RemoteOK", desc, "Remote", posted));
            }
        } catch (Exception e) { out.add(err("RemoteOK: " + e.getMessage())); }
        return out;
    }

    // ---------- WORKDAY CXS ----------
    private static final int WD_LIST_PAGES = 3, WD_LIST_LIMIT = 20, WD_DETAIL_CAP = 20;

    /** token = "tenant|wdN|site", e.g. "nvidia|wd5|NVIDIAExternalCareerSite". Pulls the
     * public Workday CXS JSON (no auth): list postings, title pre-filter, then fetch
     * survivor JDs in parallel. Port of sources.py workday(). */
    public List<JobPosting> workday(String token, boolean newGradOnly, int maxYears,
                                    List<String> focusKeys, Double maxAgeHours, String displayName) {
        String[] parts = token == null ? new String[0] : token.split("\\|");
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank())
            return List.of(err("Workday " + token + ": token must be 'tenant|wdN|site'"));
        String tenant = parts[0].strip(), wd = parts[1].strip(), site = parts[2].strip();
        String base = "https://" + tenant + "." + wd + ".myworkdayjobs.com";
        String company = displayName != null ? displayName : titleCase(tenant);
        List<JobPosting> out = new ArrayList<>();
        try {
            List<String[]> survivors = new ArrayList<>();   // [title, externalPath]
            Set<String> seen = new HashSet<>();
            for (int page = 0; page < WD_LIST_PAGES; page++) {
                JsonNode listing;
                try {
                    String body = mapper.writeValueAsString(Map.of("appliedFacets", Map.of(),
                            "limit", WD_LIST_LIMIT, "offset", page * WD_LIST_LIMIT, "searchText", focusText(focusKeys)));
                    listing = postJson(base + "/wday/cxs/" + tenant + "/" + site + "/jobs", body);
                } catch (Exception e) { break; }
                JsonNode batch = listing.path("jobPostings");
                int n = 0;
                for (JsonNode p : batch) {
                    n++;
                    String title = txt(p, "title").strip();
                    String ep = txt(p, "externalPath");
                    if (ep.isBlank() || !seen.add(ep)) continue;
                    if (newGradOnly && (TextUtils.isSeniorTitle(title) || !Roles.titleMatchesFocus(title, focusKeys))) continue;
                    survivors.add(new String[]{title, ep});
                    if (survivors.size() >= WD_DETAIL_CAP) break;
                }
                if (survivors.size() >= WD_DETAIL_CAP || n < WD_LIST_LIMIT) break;
            }
            for (JsonNode info : fetchDetails(survivors, item -> {
                try { return postJsonOrGet(base + "/wday/cxs/" + tenant + "/" + site + item[1]).path("jobPostingInfo"); }
                catch (Exception e) { return null; }
            })) {
                String rtitle = info.path("title").asText("");
                String content = stripHtml(txt(info, "jobDescription"));
                if (newGradOnly && !entryLevelSwe(rtitle, content, maxYears, focusKeys)) continue;
                String posted = info.path("startDate").asText(null);
                if (PostedDate.tooOld(posted, maxAgeHours)) continue;
                String loc = txt(info, "location");
                if (foreign(loc)) continue;
                String link = info.hasNonNull("externalUrl") ? txt(info, "externalUrl") : base + "/" + site;
                out.add(job(rtitle, company, loc, link, "Workday", content, TextUtils.detectWorkMode(loc + " " + content), posted));
            }
        } catch (Exception e) { out.add(err("Workday " + tenant + ": " + e.getMessage())); }
        return out;
    }

    // ---------- SMARTRECRUITERS ----------
    /** token = companyId, e.g. "Visa". Public Posting API (no auth). Port of
     * sources.py smartrecruiters(). */
    public List<JobPosting> smartrecruiters(String token, boolean newGradOnly, int maxYears,
                                            List<String> focusKeys, Double maxAgeHours, String displayName) {
        String company = displayName != null ? displayName : titleCase(token);
        String base = "https://api.smartrecruiters.com/v1/companies/" + token + "/postings";
        List<JobPosting> out = new ArrayList<>();
        try {
            JsonNode listing = getJson(base + "?limit=100&q=" + enc(focusText(focusKeys)), null);
            List<String[]> survivors = new ArrayList<>();   // [id, title, loc, posted]
            for (JsonNode j : listing.path("content")) {
                String title = txt(j, "name").strip();
                JsonNode loc = j.path("location");
                String locStr = String.join(", ", List.of(loc.path("city").asText(""),
                        loc.path("region").asText(""), loc.path("country").asText("")).stream().filter(s -> !s.isBlank()).toList());
                if (newGradOnly && (TextUtils.isSeniorTitle(title) || !Roles.titleMatchesFocus(title, focusKeys))) continue;
                String posted = j.path("releasedDate").asText(null);
                if (PostedDate.tooOld(posted, maxAgeHours)) continue;
                survivors.add(new String[]{txt(j, "id"), title, locStr, posted == null ? "" : posted});
                if (survivors.size() >= WD_DETAIL_CAP) break;
            }
            for (String[] s : survivors) {
                String content = "";
                try {
                    JsonNode d = getJson(base + "/" + s[0], null);
                    JsonNode secs = d.path("jobAd").path("sections");
                    content = stripHtml(secs.path("jobDescription").path("text").asText("") + " "
                            + secs.path("qualifications").path("text").asText("") + " "
                            + secs.path("additionalInformation").path("text").asText(""));
                } catch (Exception ignore) {}
                if (newGradOnly && TextUtils.extractYearsRequired(content) > maxYears) continue;
                if (newGradOnly && Roles.requiresNewGradSignal(focusKeys) && !Roles.isNewGradRole(s[1], content)) continue;
                if (foreign(s[2])) continue;
                out.add(job(s[1], company, s[2], "https://jobs.smartrecruiters.com/" + token + "/" + s[0],
                        "SmartRecruiters", content, TextUtils.detectWorkMode(s[2] + " " + content),
                        s[3].isBlank() ? null : s[3]));
            }
        } catch (Exception e) { out.add(err("SmartRecruiters " + token + ": " + e.getMessage())); }
        return out;
    }

    // ---------- shared workday/SR helpers ----------
    private String focusText(List<String> focusKeys) {
        return "software engineer";
    }

    private String titleCase(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private JsonNode postJsonOrGet(String url) throws Exception {
        return getJson(url, Map.of("Accept", "application/json"));
    }

    /** Fetch detail JDs for survivor items in parallel (cap 6 workers). */
    private List<JsonNode> fetchDetails(List<String[]> survivors, java.util.function.Function<String[], JsonNode> fn) {
        List<JsonNode> out = new ArrayList<>();
        if (survivors.isEmpty()) return out;
        java.util.concurrent.ExecutorService ex =
                java.util.concurrent.Executors.newFixedThreadPool(Math.min(6, survivors.size()));
        try {
            List<java.util.concurrent.Future<JsonNode>> fs = new ArrayList<>();
            for (String[] item : survivors) fs.add(ex.submit(() -> fn.apply(item)));
            for (var f : fs) { try { JsonNode n = f.get(); if (n != null && !n.isMissingNode()) out.add(n); } catch (Exception ignore) {} }
        } finally { ex.shutdown(); }
        return out;
    }
}
