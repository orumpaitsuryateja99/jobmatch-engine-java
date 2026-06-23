package com.orumpati.jobmatch.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orumpati.jobmatch.model.JobPosting;
import com.orumpati.jobmatch.roles.Roles;
import com.orumpati.jobmatch.text.TextUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Java port of the live, ToS-permitted fetchers in app/sources.py:
 * Greenhouse Job Board API, Lever Postings API, Ashby Posting API, and The Muse
 * public jobs API. Deliberately does NOT scrape LinkedIn/Indeed/Dice/etc.
 *
 * SerpApi / Tavily discovery, JSearch, Adzuna, Workday CXS, and the AI-search
 * paste path from the Python app are NOT ported here — they either need paid
 * API keys or are a much larger surface; this covers the no-key ATS-board core. */
@Service
public class JobBoardClient {

    private static final int MAX_YEARS_FOR_NEWGRAD = 2;
    // Force HTTP/1.1: the default HTTP/2 client multiplexes every request to the
    // SAME host (all 141 Greenhouse boards share boards-api.greenhouse.io) over a
    // single connection, serializing a concurrent sponsor search. HTTP/1.1 opens a
    // separate pooled connection per concurrent call, so boards fetch in parallel.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "JobMatchEngine/1.0 (personal job-search assistant)")
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + url);
        }
        return mapper.readTree(resp.body());
    }

    private static final Pattern HTML_TAG_RE = Pattern.compile("<[^>]+>");

    private String stripHtml(String s) {
        if (s == null) return "";
        // Greenhouse HTML-entity-encodes its markup, e.g. "&lt;p&gt;Who&lt;/p&gt;".
        // Decode the tag entities FIRST so they become real tags, THEN strip — else
        // the encoded tags survive the regex and leak literal "<p>" into descriptions
        // (which also pollutes downstream ATS keyword matching). Decode "&amp;" LAST
        // so a literal "&amp;lt;" isn't turned into a strippable tag.
        String t = s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        t = HTML_TAG_RE.matcher(t).replaceAll(" ");
        t = t.replace("&amp;", "&");
        return t.strip().replaceAll("\\s+", " ");
    }

    private boolean passesEntryLevel(String title, String content, int maxYears) {
        if (TextUtils.isSeniorTitle(title)) return false;
        return TextUtils.extractYearsRequired(content) <= maxYears;
    }

    private boolean isEntryLevelSwe(String title, String content, int maxYears, List<String> focusKeys) {
        if (!Roles.titleMatchesFocus(title, focusKeys)) return false;
        if (Roles.requiresNewGradSignal(focusKeys) && !Roles.isNewGradRole(title, content)) return false;
        return passesEntryLevel(title, content, maxYears);
    }

    /** True when the location is explicitly outside the US (Berlin, London, Europe, …).
     *  Board APIs (Greenhouse/Lever/Ashby/Muse) carry foreign roles too, so drop them —
     *  the aggregator/discovery sources already filter this. Empty/Remote/unknown kept. */
    private boolean foreignLocation(String loc) {
        return "foreign".equals(TextUtils.detectUsLocation(loc));
    }

    /** board_token is the company's Greenhouse slug, e.g. "stripe", "databricks". */
    public List<JobPosting> greenhouse(String boardToken, boolean newGradOnly, int maxYears,
                                        List<String> focusKeys, Double maxAgeHours, String displayName) {
        List<JobPosting> out = new ArrayList<>();
        String url = "https://boards-api.greenhouse.io/v1/boards/" + boardToken + "/jobs?content=true";
        String company = displayName != null ? displayName : titleCase(boardToken.replace("-", " "));
        try {
            JsonNode root = getJson(url);
            for (JsonNode j : root.path("jobs")) {
                String title = j.path("title").asText("");
                String content = stripHtml(j.path("content").asText(""));
                if (newGradOnly && !isEntryLevelSwe(title, content, maxYears, focusKeys)) continue;
                String posted = j.hasNonNull("first_published") ? j.path("first_published").asText()
                        : j.path("updated_at").asText(null);
                if (PostedDate.tooOld(posted, maxAgeHours)) continue;
                String loc = j.path("location").path("name").asText("");
                if (foreignLocation(loc)) continue;
                JobPosting jp = new JobPosting();
                jp.setTitle(title);
                jp.setCompany(company);
                jp.setBoardToken(boardToken);
                jp.setLocation(loc);
                jp.setJobLink(j.path("absolute_url").asText(""));
                jp.setSource("Greenhouse");
                jp.setDescription(content);
                jp.setWorkMode(TextUtils.detectWorkMode(loc + " " + content));
                jp.setPostedDate(PostedDate.postedDateStr(posted));
                out.add(jp);
            }
        } catch (Exception e) {
            out.add(error("Greenhouse " + boardToken + ": " + e.getMessage()));
        }
        return out;
    }

    /** company is the Lever slug, e.g. "leverdemo", "plaid". */
    public List<JobPosting> lever(String company, boolean newGradOnly, int maxYears,
                                   List<String> focusKeys, Double maxAgeHours, String displayName) {
        List<JobPosting> out = new ArrayList<>();
        String url = "https://api.lever.co/v0/postings/" + company + "?mode=json";
        String companyName = displayName != null ? displayName : titleCase(company.replace("-", " "));
        try {
            JsonNode root = getJson(url);
            for (JsonNode j : root) {
                String title = j.path("text").asText("");
                String contentRaw = j.hasNonNull("descriptionPlain") ? j.path("descriptionPlain").asText()
                        : j.path("description").asText("");
                String content = stripHtml(contentRaw);
                if (newGradOnly && !isEntryLevelSwe(title, content, maxYears, focusKeys)) continue;
                String posted = j.path("createdAt").asText(null);
                if (PostedDate.tooOld(posted, maxAgeHours)) continue;
                JsonNode cats = j.path("categories");
                String loc = cats.path("location").asText("");
                if (foreignLocation(loc)) continue;   // primary location only
                String wp = cats.path("workplaceType").asText("");
                JobPosting jp = new JobPosting();
                jp.setTitle(title);
                jp.setCompany(companyName);
                jp.setBoardToken(company);
                jp.setLocation(loc);
                jp.setJobLink(j.path("hostedUrl").asText(""));
                jp.setSource("Lever");
                jp.setDescription(content);
                jp.setWorkMode(TextUtils.detectWorkMode(wp + " " + loc + " " + content));
                jp.setPostedDate(PostedDate.postedDateStr(posted));
                out.add(jp);
            }
        } catch (Exception e) {
            out.add(error("Lever " + company + ": " + e.getMessage()));
        }
        return out;
    }

    /** company is the Ashby job-board name, e.g. "ramp", "openai", "notion". */
    public List<JobPosting> ashby(String company, boolean newGradOnly, int maxYears,
                                   List<String> focusKeys, Double maxAgeHours, String displayName) {
        List<JobPosting> out = new ArrayList<>();
        String url = "https://api.ashbyhq.com/posting-api/job-board/" + company + "?includeCompensation=true";
        String companyName = displayName != null ? displayName : titleCase(company.replace("-", " "));
        try {
            JsonNode root = getJson(url);
            for (JsonNode j : root.path("jobs")) {
                String title = j.path("title").asText("");
                String contentRaw = j.hasNonNull("descriptionPlain") ? j.path("descriptionPlain").asText()
                        : j.path("descriptionHtml").asText("");
                String content = stripHtml(contentRaw);
                if (newGradOnly && !isEntryLevelSwe(title, content, maxYears, focusKeys)) continue;
                String posted = j.hasNonNull("publishedAt") ? j.path("publishedAt").asText()
                        : j.path("updatedAt").asText(null);
                if (PostedDate.tooOld(posted, maxAgeHours)) continue;
                JsonNode locNode = j.path("location");
                String loc = locNode.isTextual() ? locNode.asText()
                        : locNode.path("locationName").asText(locNode.path("name").asText(""));
                String country = j.path("address").path("postalAddress").path("addressCountry").asText("");
                if (foreignLocation(loc + " " + country)) continue;   // primary location only
                String remote = j.path("isRemote").asBoolean(false) ? "remote" : "";
                JobPosting jp = new JobPosting();
                jp.setTitle(title);
                jp.setCompany(companyName);
                jp.setBoardToken(company);
                jp.setLocation(loc);
                jp.setJobLink(j.hasNonNull("jobUrl") ? j.path("jobUrl").asText() : j.path("applyUrl").asText(""));
                jp.setSource("Ashby");
                jp.setDescription(content);
                jp.setWorkMode(TextUtils.detectWorkMode(remote + " " + loc + " " + content));
                jp.setPostedDate(PostedDate.postedDateStr(posted));
                out.add(jp);
            }
        } catch (Exception e) {
            out.add(error("Ashby " + company + ": " + e.getMessage()));
        }
        return out;
    }

    private static final String MUSE_URL = "https://www.themuse.com/api/public/jobs";
    private static final List<String> MUSE_CATEGORIES = List.of(
            "Software Engineering", "Data Science", "Data and Analytics", "Engineering");

    /** The Muse public jobs API — no API key required. Pulls several adjacent
     * categories since The Muse files SWE roles under more than just "Software
     * Engineering"; every role is still gated through the same focus + entry-level
     * filters. Stops paging on the first empty/failed page. */
    public List<JobPosting> themuse(boolean newGradOnly, int maxYears, List<String> focusKeys,
                                     Double maxAgeHours, int maxPages) {
        List<JobPosting> out = new ArrayList<>();
        try {
            StringBuilder catParams = new StringBuilder();
            for (String c : MUSE_CATEGORIES) {
                catParams.append("&category=").append(java.net.URLEncoder.encode(c, java.nio.charset.StandardCharsets.UTF_8));
            }
            for (int page = 0; page < Math.max(1, maxPages); page++) {
                String url = MUSE_URL + "?" + catParams.substring(1) + "&page=" + page;
                JsonNode data;
                try {
                    data = getJson(url);
                } catch (Exception e) {
                    break;
                }
                JsonNode results = data.path("results");
                if (!results.isArray() || results.isEmpty()) break;
                for (JsonNode j : results) {
                    String title = j.path("name").asText("").strip();
                    String content = stripHtml(j.path("contents").asText(""));
                    if (newGradOnly && !isEntryLevelSwe(title, content, maxYears, focusKeys)) continue;
                    String posted = j.path("publication_date").asText(null);
                    if (PostedDate.tooOld(posted, maxAgeHours)) continue;
                    List<String> locs = new ArrayList<>();
                    for (JsonNode l : j.path("locations")) {
                        String n = l.path("name").asText("");
                        if (!n.isEmpty()) locs.add(n);
                    }
                    if (foreignLocation(String.join("; ", locs))) continue;
                    JobPosting jp = new JobPosting();
                    jp.setTitle(title);
                    jp.setCompany(j.path("company").path("name").asText(""));
                    jp.setLocation(String.join("; ", locs));
                    jp.setJobLink(j.path("refs").path("landing_page").asText(""));
                    jp.setSource("The Muse");
                    jp.setDescription(content);
                    jp.setWorkMode(TextUtils.detectWorkMode(jp.getLocation() + " " + content));
                    jp.setPostedDate(PostedDate.postedDateStr(posted));
                    out.add(jp);
                }
            }
        } catch (Exception e) {
            out.add(error("The Muse: " + e.getMessage()));
        }
        return out;
    }

    private JobPosting error(String message) {
        JobPosting jp = new JobPosting();
        jp.setError(message);
        return jp;
    }

    private String titleCase(String s) {
        String[] parts = s.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    // ---- de-duplication (job_key / dedupe from sources.py) ----

    private String jobKey(JobPosting j) {
        String link = (j.getJobLink() == null ? "" : j.getJobLink()).toLowerCase();
        if (!link.isBlank()) return link;
        return (j.getCompany() + "|" + j.getTitle() + "|" + j.getLocation()).toLowerCase().strip();
    }

    public List<JobPosting> dedupe(List<JobPosting> jobs) {
        Set<String> seen = new HashSet<>();
        List<JobPosting> out = new ArrayList<>();
        for (JobPosting j : jobs) {
            if (j.getError() != null) { out.add(j); continue; }
            String k = jobKey(j);
            if (seen.add(k)) out.add(j);
        }
        return out;
    }
}
