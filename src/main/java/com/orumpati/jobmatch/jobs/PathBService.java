package com.orumpati.jobmatch.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orumpati.jobmatch.model.JobPosting;
import com.orumpati.jobmatch.model.Profile;
import com.orumpati.jobmatch.roles.Roles;
import com.orumpati.jobmatch.text.TextUtils;
import org.springframework.stereotype.Service;

import java.util.*;

/** Java port of app/prompts.py build_job_search_prompt + sources.py
 * normalize_pasted_jobs — Path B (AI Search → Paste JSON). No LLM call server-side:
 * we generate a CO-STAR prompt the user runs in Claude.ai / ChatGPT (with web
 * search), then paste the JSON array back here to import + filter the jobs. */
@Service
public class PathBService {

    private static final List<String> ALLOWED_SOURCES = List.of(
            "LinkedIn (linkedin.com/jobs)", "Indeed (indeed.com)", "Glassdoor (glassdoor.com/Job)",
            "Dice (dice.com)", "Wellfound (wellfound.com/jobs)", "Handshake (joinhandshake.com)",
            "Jobright AI (jobright.ai)", "MyVisaJobs (myvisajobs.com)", "OPTnation (optnation.com)",
            "Interstride (interstride.com/jobs)", "RippleMatch (ripplematch.com)", "Lensa (lensa.com)",
            "Built In (builtin.com)", "Y Combinator Jobs (ycombinator.com/jobs)", "Startup Jobs (startup.jobs)");

    private final ObjectMapper mapper = new ObjectMapper();

    public String buildSearchPrompt(Profile p, String prefs, String location, int maxYears,
                                    List<String> roleLabels, String workMode, boolean h1bOnly,
                                    String freshnessLabel) {
        List<String> sk = new ArrayList<>();
        if (p.getSkills() != null) sk.addAll(p.getSkills());
        if (p.getTools() != null) sk.addAll(p.getTools());
        String skills = sk.isEmpty() ? "(see resume)" : String.join(", ", sk.subList(0, Math.min(14, sk.size())));
        String roles = String.join(", ", p.getTargetRoles() == null || p.getTargetRoles().isEmpty()
                ? List.of("software engineer") : p.getTargetRoles());
        String focus = roleLabels == null || roleLabels.isEmpty() ? "Software Engineer (general)" : String.join(", ", roleLabels);
        String summary = p.getSummary() == null ? "" : p.getSummary();
        String rt = p.getRawText() == null ? "" : p.getRawText().strip();
        String resumeBlock = rt.isEmpty() ? "(only the structured profile above is available)"
                : rt.substring(0, Math.min(6000, rt.length()));
        String fl = freshnessLabel == null || freshnessLabel.isBlank() ? "the last 24 hours" : freshnessLabel;

        String workLine = (workMode != null && !workMode.isBlank() && !workMode.equalsIgnoreCase("any"))
                ? "- WORK ARRANGEMENT: return ONLY **" + workMode + "** roles. Confirm the posting says "
                  + workMode.toLowerCase() + " (or 0-2 days in office for hybrid). Drop roles that don't match."
                : "- WORK ARRANGEMENT: any (remote, hybrid, or onsite all fine). Still fill the work_mode field from the posting.";
        String h1bLine = h1bOnly
                ? "- H1B IS MANDATORY: return ONLY employers with a recent, verifiable H1B sponsorship history "
                  + "(check MyVisaJobs / H1BGrader). Drop any company that does not sponsor. In h1b_note, state the evidence and the word verify."
                : "- H1B: STRONGLY PREFER employers with H1B sponsorship history; rank them first. In h1b_note say whether sponsorship looks likely and include verify.";

        StringBuilder allowed = new StringBuilder();
        for (String s : ALLOWED_SOURCES) allowed.append("\n   • ").append(s);

        return """
# Job Search Assistant (CO-STAR)

## C - Context
You are my job-search assistant WITH LIVE WEB SEARCH. I am an ENTRY-LEVEL / NEW-GRAD
software engineer (MS Computer Science). Use my profile below to find roles that
genuinely match my REAL background. Application stays manual — you only return links.
- ROLE FOCUS (what to search for): %s
- Target job titles to match: %s
- Top skills: %s
- Location preference: %s
- Other preferences: %s
- Summary: %s

### MY FULL RÉSUMÉ (match roles against ALL of this — projects, bullets, metrics, tools)
%s

## O - Objective
Return a JSON array of up to 25 CURRENTLY OPEN, ENTRY-LEVEL / NEW-GRAD jobs that match the
ROLE FOCUS above — each with the EXACT live posting link and the FULL job description copied
from that posting. Quality over quantity.

## ALLOWED SOURCES — SEARCH ONLY THESE SITES (no exceptions)%s

## HARD RULES
### RULE 1 — FRESHNESS: ONLY roles posted within %s. If the date is older or not visible → DROP.
### RULE 2 — LINK MUST BE A SINGLE LIVE JOB PAGE (open it; no search/homepage/404/redirect).
### RULE 3 — NEW GRAD / ENTRY LEVEL ONLY. Title/JD must signal new-grad/entry/associate/0-%d yrs.
   EXCLUDE Senior/Sr./Staff/Principal/Lead/Manager/Director/Architect/II/III/IV/L3+/SWE 2+.
### RULE 4 — NO MEMORY / NO GUESSING: every job from a live page opened this session.
### RULE 5 — RÉSUMÉ RELEVANCE MANDATORY: the JD's real requirements must overlap mine.
### RULE 6 — EXCLUDE contract/C2C/1099, unpaid, internships, commission-only, staffing reposts.
%s
%s
### RULE 7 — NO DUPLICATES / NO PADDING. If fewer than 25 pass, RETURN FEWER. If none, return [].

## T - Output (return ONLY this JSON array — no markdown, no commentary)
[
  {
    "company": "", "role": "", "location": "", "work_mode": "Remote / Hybrid / Onsite",
    "source": "which allowed board you found it on",
    "apply_link": "exact live single-posting URL (company ATS or board direct link)",
    "job_description": "full text copied verbatim from the posting",
    "fit_score": 0, "fit_reason": "one sentence specific to my background",
    "matched_skills": ["skills from my resume that match"],
    "gaps": ["requirements I should NOT fake"],
    "experience_required": "", "posted_date": "exact date or 'X hours ago' or 'evergreen / rolling'",
    "salary": "", "h1b_note": "short note, always include the word verify",
    "priority": "Apply immediately / Tailor first / Skip"
  }
]

## R - Reflection (run through ALL before returning)
1. Every job from a live page opened this session — zero from memory?
2. Every job from one of the allowed boards above — NO other sites?
3. Every apply_link a single live job page (no 404/redirect to search/homepage)?
4. Every posted_date within %s or explicitly "evergreen / rolling"?
5. Every role matches the ROLE FOCUS (%s)? Drop off-focus roles.
6. Dropped all Senior/Staff/Lead/II+/%d-yr+ roles?
7. No duplicates? Return fewer, not padded guesses.

### THEN: paste the JSON array back into the app's "Paste AI results" box.
""".formatted(focus, roles, skills, location == null ? "any" : location,
                prefs == null || prefs.isBlank() ? "(none)" : prefs, summary, resumeBlock,
                allowed.toString(), fl, maxYears, workLine, h1bLine, fl, focus, maxYears);
    }

    public record ImportResult(List<JobPosting> jobs, int kept, int filteredSenior,
                               int filteredFocus, int filteredDupes, boolean empty, String error) {}

    public ImportResult normalizePastedJobs(String text, boolean newGradOnly, int maxYears, List<String> focusKeys) {
        JsonNode arr = extractJsonArray(text);
        if (arr == null) {
            return new ImportResult(List.of(), 0, 0, 0, 0, false,
                    "No JSON array found. Paste the AI's output as a JSON array — e.g. " +
                    "[{\"company\":\"…\",\"role\":\"…\",\"apply_link\":\"…\",\"job_description\":\"…\"}]. Code fences (```json) are OK.");
        }
        if (arr.isEmpty()) {
            return new ImportResult(List.of(), 0, 0, 0, 0, true, null);
        }
        List<JobPosting> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int senior = 0, offFocus = 0, dupes = 0;
        for (JsonNode j : arr) {
            if (!j.isObject()) continue;
            String title = first(j, "role", "title", "job_title").strip();
            String company = j.path("company").asText("").strip();
            if (title.isEmpty() || company.isEmpty()) continue;
            String desc = first(j, "job_description", "description", "jd").strip();
            String exp = first(j, "experience_required", "experienceRequired");
            if (newGradOnly && !passesEntryLevel(title, desc + " " + exp, maxYears)) { senior++; continue; }
            if (!Roles.titleMatchesFocus(title, focusKeys)) { offFocus++; continue; }
            String loc = j.path("location").asText("").strip();
            String wm = first(j, "work_mode", "workMode", "remote");
            String link = first(j, "apply_link", "applyLink", "application_url", "url", "link");
            String key = link.isBlank() ? (company + "|" + title + "|" + loc).toLowerCase() : link.toLowerCase();
            if (!seen.add(key)) { dupes++; continue; }
            JobPosting jp = new JobPosting();
            jp.setTitle(title); jp.setCompany(company); jp.setLocation(loc);
            jp.setJobLink(link); jp.setSource(firstNonBlank(j.path("source").asText(""), "AI search"));
            jp.setDescription(desc);
            jp.setWorkMode(TextUtils.detectWorkMode(wm + " " + loc + " " + desc));
            jp.setPostedDate(first(j, "posted_date", "postedDate", "job_freshness"));
            kept.add(jp);
        }
        return new ImportResult(kept, kept.size(), senior, offFocus, dupes, false, null);
    }

    private boolean passesEntryLevel(String title, String content, int maxYears) {
        if (TextUtils.isSeniorTitle(title)) return false;
        return TextUtils.extractYearsRequired(content) <= maxYears;
    }

    private String first(JsonNode n, String... fields) {
        for (String f : fields) if (n.hasNonNull(f)) return n.path(f).asText("");
        return "";
    }

    private String firstNonBlank(String a, String b) { return a == null || a.isBlank() ? b : a; }

    /** Pull the first JSON array out of pasted text, tolerating ```json fences and
     * surrounding prose. Also accepts {"jobs":[…]}. */
    private JsonNode extractJsonArray(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        try {
            JsonNode node = mapper.readTree(t);
            if (node.isArray()) return node;
            if (node.isObject() && node.path("jobs").isArray()) return node.path("jobs");
        } catch (Exception ignore) { /* fall through to substring scan */ }
        int start = t.indexOf('[');
        int end = t.lastIndexOf(']');
        if (start >= 0 && end > start) {
            try {
                JsonNode node = mapper.readTree(t.substring(start, end + 1));
                if (node.isArray()) return node;
            } catch (Exception ignore) { /* not parseable */ }
        }
        return null;
    }
}
