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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java port of the Discovery pipeline in app/sources.py — use a web-search API
 * (provider auto-detected: SerpApi → Brave → Bing → Tavily → Google PSE, from the
 * same .env keys) to find individual job postings on LinkedIn / Indeed / Greenhouse
 * / etc., then normalize each result into a JobPosting (entry-level + role-focus
 * gated, aggregation/search pages dropped). */
@Service
public class DiscoveryService {

    /** site label -> Google-style site: clause (subset that has per-posting URLs). */
    public static final LinkedHashMap<String, String> SITES = new LinkedHashMap<>();
    static {
        SITES.put("Company careers", "(site:job-boards.greenhouse.io OR site:greenhouse.io OR site:jobs.lever.co OR site:jobs.ashbyhq.com OR site:myworkdayjobs.com OR site:jobs.smartrecruiters.com)");
        SITES.put("Google postings", "(site:greenhouse.io OR site:jobs.lever.co OR site:jobs.ashbyhq.com OR site:myworkdayjobs.com)");
        SITES.put("LinkedIn Jobs", "site:linkedin.com/jobs/view");
        SITES.put("Indeed", "site:indeed.com/viewjob");
        SITES.put("Glassdoor", "site:glassdoor.com/job-listing");
        SITES.put("Dice", "site:dice.com/job-detail");
        SITES.put("Built In", "site:builtin.com/job");
        SITES.put("Wellfound", "site:wellfound.com/jobs");
        SITES.put("Jobright AI", "site:jobright.ai/jobs");
        SITES.put("Startup boards", "(site:startup.jobs OR site:ycombinator.com/jobs OR site:workatastartup.com)");
        SITES.put("H1B/OPT boards", "(site:myvisajobs.com OR site:optnation.com OR site:interstride.com)");
        SITES.put("New-grad aggregators", "(site:ripplematch.com OR site:lensa.com)");
    }

    private static final String ROLE_CLAUSE = "new grad software engineer";
    private static final String TAIL = "(\"new grad\" OR \"entry level\" OR \"junior\" OR \"associate\" OR \"software engineer I\")";
    private static final Pattern SITE_SUFFIX_RE = Pattern.compile(
            "\\s*[-|–—]\\s*(LinkedIn|Indeed.*|Glassdoor|Dice|Built In|Wellfound|SmartRecruiters|Greenhouse|Lever|Ashby|Workday|Jobright.*|jobs?).*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AT_COMPANY_RE = Pattern.compile("\\bat\\s+([A-Z][\\w.&'+ ]{1,40}?)\\s*$");
    private static final Pattern HIRING_RE = Pattern.compile("(.+?)\\s+hiring\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATS_SLUG_RE = Pattern.compile(
            "(?:greenhouse\\.io/|lever\\.co/|ashbyhq\\.com/|smartrecruiters\\.com/)([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AGG_TITLE_RE = Pattern.compile(
            "\\b(\\d+\\+?\\s+jobs?|jobs?,\\s*employment|jobs?\\s*\\(|jobs in|\\d+\\s+open|open now|hiring now|"
            + "job openings|careers|job search|search results|browse jobs|all jobs)\\b|\\$\\d",
            Pattern.CASE_INSENSITIVE);

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApiKeys keys;

    public DiscoveryService(ApiKeys keys) {
        this.keys = keys;
    }

    public List<String> labels() { return new ArrayList<>(SITES.keySet()); }

    /** Auto-detected provider, same precedence as _discovery_config. "" if none. */
    public String provider() {
        if (!keys.get("SERPAPI_API_KEY").isBlank()) return "serpapi";
        if (!keys.get("BRAVE_API_KEY").isBlank()) return "brave";
        if (!keys.get("BING_API_KEY").isBlank()) return "bing";
        if (!keys.get("TAVILY_API_KEY").isBlank()) return "tavily";
        if (!keys.get("GOOGLE_API_KEY").isBlank() && !keys.get("GOOGLE_CX").isBlank()) return "google_pse";
        return "";
    }

    public boolean available() { return !provider().isBlank(); }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }

    /** Run discovery for the chosen site labels and normalize to jobs. */
    public List<JobPosting> search(List<String> siteLabels, boolean newGradOnly, int maxYears,
                                   List<String> focusKeys, Double maxAgeHours, int maxResults) {
        String prov = provider();
        if (prov.isBlank()) return List.of(errJob("Discovery: no search provider configured"));
        List<String> labels = siteLabels == null || siteLabels.isEmpty() ? labels() : siteLabels;
        List<JobPosting> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String label : labels) {
            String site = SITES.get(label);
            if (site == null) continue;
            String q = site + " " + ROLE_CLAUSE + " " + TAIL;
            List<String[]> results;     // [title, url, snippet]
            try {
                results = searchProvider(prov, q, Math.min(maxResults, 10), maxAgeHours);
            } catch (Exception e) {
                out.add(errJob("Discovery " + label + ": " + e.getMessage()));
                continue;
            }
            for (String[] r : results) {
                JobPosting jp = resolveLead(label, r[0], r[1], r[2], newGradOnly, maxYears, focusKeys);
                if (jp == null) continue;
                String key = jp.getJobLink().toLowerCase();
                if (key.isBlank() || !seen.add(key)) continue;
                out.add(jp);
            }
        }
        return out;
    }

    // ---------- provider HTTP ----------
    private List<String[]> searchProvider(String prov, String q, int n, Double maxAge) throws Exception {
        return switch (prov) {
            case "serpapi" -> serpapi(q, n, maxAge);
            case "brave" -> brave(q, n, maxAge);
            case "bing" -> bing(q, n);
            case "tavily" -> tavily(q, n, maxAge);
            default -> googlePse(q, n, maxAge);
        };
    }

    private JsonNode get(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json")
                .timeout(Duration.ofSeconds(12)).GET();
        if (headers != null) headers.forEach(b::header);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode());
        return mapper.readTree(resp.body());
    }

    private List<String[]> serpapi(String q, int n, Double maxAge) throws Exception {
        String tbs = maxAge == null ? "" : (maxAge <= 24 ? "&tbs=qdr:d" : maxAge <= 168 ? "&tbs=qdr:w" : maxAge <= 720 ? "&tbs=qdr:m" : "");
        JsonNode r = get("https://serpapi.com/search?engine=google&gl=us&hl=en&num=" + n
                + "&api_key=" + enc(keys.get("SERPAPI_API_KEY")) + "&q=" + enc(q) + tbs, null);
        List<String[]> out = new ArrayList<>();
        for (JsonNode it : r.path("organic_results"))
            out.add(new String[]{it.path("title").asText(""), it.path("link").asText(""), it.path("snippet").asText("")});
        return out;
    }

    private List<String[]> brave(String q, int n, Double maxAge) throws Exception {
        String fresh = maxAge == null ? "" : (maxAge <= 24 ? "&freshness=pd" : maxAge <= 168 ? "&freshness=pw" : maxAge <= 720 ? "&freshness=pm" : "");
        JsonNode r = get("https://api.search.brave.com/res/v1/web/search?count=" + Math.min(n, 20) + "&safesearch=off&q=" + enc(q) + fresh,
                Map.of("X-Subscription-Token", keys.get("BRAVE_API_KEY"), "Accept", "application/json"));
        List<String[]> out = new ArrayList<>();
        for (JsonNode it : r.path("web").path("results"))
            out.add(new String[]{it.path("title").asText(""), it.path("url").asText(""), it.path("description").asText("")});
        return out;
    }

    private List<String[]> bing(String q, int n) throws Exception {
        JsonNode r = get("https://api.bing.microsoft.com/v7.0/search?count=" + Math.min(n, 20) + "&q=" + enc(q),
                Map.of("Ocp-Apim-Subscription-Key", keys.get("BING_API_KEY")));
        List<String[]> out = new ArrayList<>();
        for (JsonNode it : r.path("webPages").path("value"))
            out.add(new String[]{it.path("name").asText(""), it.path("url").asText(""), it.path("snippet").asText("")});
        return out;
    }

    private List<String[]> tavily(String q, int n, Double maxAge) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("api_key", keys.get("TAVILY_API_KEY"));
        payload.put("query", q);
        payload.put("max_results", n);
        payload.put("search_depth", "advanced");
        if (maxAge != null) payload.put("time_range", maxAge <= 24 ? "day" : maxAge <= 168 ? "week" : maxAge <= 744 ? "month" : "year");
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.tavily.com/search"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode());
        JsonNode r = mapper.readTree(resp.body());
        List<String[]> out = new ArrayList<>();
        for (JsonNode it : r.path("results"))
            out.add(new String[]{it.path("title").asText(""), it.path("url").asText(""), it.path("content").asText("")});
        return out;
    }

    private List<String[]> googlePse(String q, int n, Double maxAge) throws Exception {
        String dr = "";
        if (maxAge != null) { int days = (int) Math.max(1, Math.ceil(maxAge / 24.0)); dr = "&dateRestrict=d" + Math.min(days, 365); }
        JsonNode r = get("https://www.googleapis.com/customsearch/v1?num=" + Math.min(n, 10)
                + "&key=" + enc(keys.get("GOOGLE_API_KEY")) + "&cx=" + enc(keys.get("GOOGLE_CX")) + "&q=" + enc(q) + dr, null);
        List<String[]> out = new ArrayList<>();
        for (JsonNode it : r.path("items"))
            out.add(new String[]{it.path("title").asText(""), it.path("link").asText(""), it.path("snippet").asText("")});
        return out;
    }

    // ---------- lead -> job ----------
    private JobPosting resolveLead(String label, String rawTitle, String url, String snippet,
                                   boolean newGradOnly, int maxYears, List<String> focusKeys) {
        if (url == null || url.isBlank() || rawTitle == null || rawTitle.isBlank()) return null;
        if (AGG_TITLE_RE.matcher(rawTitle).find()) return null;
        String title = cleanTitle(rawTitle);
        if (title.isBlank()) title = rawTitle;
        if (!Roles.titleMatchesFocus(title, focusKeys)) return null;
        if (newGradOnly && TextUtils.isSeniorTitle(title)) return null;
        String desc = snippet == null ? "" : snippet.strip();
        if (newGradOnly && Roles.requiresNewGradSignal(focusKeys) && !Roles.isNewGradRole(title, desc)) return null;
        if (newGradOnly && TextUtils.extractYearsRequired(desc) > maxYears) return null;
        if ("foreign".equals(TextUtils.detectUsLocation(title + " " + desc))) return null;
        JobPosting jp = new JobPosting();
        jp.setTitle(title);
        jp.setCompany(leadCompany(rawTitle, url));
        jp.setLocation("");
        jp.setJobLink(url);
        jp.setSource("Discovery: " + label);
        jp.setDescription(desc);
        jp.setWorkMode(TextUtils.detectWorkMode(desc));
        jp.setPostedDate("");
        return jp;
    }

    private String cleanTitle(String title) {
        String t = title.strip().replaceFirst("(?i)^Job Application for\\s+", "");
        t = SITE_SUFFIX_RE.matcher(t).replaceAll("");
        return t.replaceAll("^[\\s\\-|–—]+|[\\s\\-|–—]+$", "").strip();
    }

    private boolean looksLikeLocation(String s) {
        s = s.strip();
        return s.matches(".*,\\s*[A-Za-z]{2}\\b.*")
                || s.matches("(?i).*\\b(united states|remote|hybrid|onsite)\\b.*")
                || s.matches("(?i).*(engineer|developer|intern|new grad|college grad|20\\d\\d).*");
    }

    private String leadCompany(String title, String url) {
        String t = title == null ? "" : title.strip();
        Matcher h = HIRING_RE.matcher(t);
        if (h.find()) {
            String c = h.group(1).strip();
            if (!c.isBlank() && !looksLikeLocation(c)) return c;
        }
        String base = SITE_SUFFIX_RE.matcher(t).replaceAll("").strip();
        Matcher at = AT_COMPANY_RE.matcher(base);
        if (at.find()) {
            String c = at.group(1).strip();
            if (!c.isBlank() && !looksLikeLocation(c)) return c;
        }
        String[] parts = base.split("\\s[-–—]\\s");
        if (parts.length >= 2) {
            for (String seg : new String[]{parts[parts.length - 1].strip(), parts[0].strip()}) {
                int w = seg.isBlank() ? 0 : seg.split("\\s+").length;
                if (w >= 1 && w <= 4 && !looksLikeLocation(seg)) return seg.replaceAll("[\\s\\-–—|]+$", "").strip();
            }
        }
        Matcher m = ATS_SLUG_RE.matcher(url == null ? "" : url);
        if (m.find()) {
            String slug = m.group(1);
            if (slug != null && !slug.equals("embed") && !slug.equals("job-boards"))
                return Arrays.stream(slug.split("-")).map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
                        .reduce((a, b) -> a + " " + b).orElse(slug);
        }
        return "";
    }

    private JobPosting errJob(String m) { JobPosting j = new JobPosting(); j.setError(m); return j; }
}
