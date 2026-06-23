package com.orumpati.jobmatch.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orumpati.jobmatch.ats.AtsResult;
import com.orumpati.jobmatch.ats.AtsScorer;
import com.orumpati.jobmatch.model.Profile;
import com.orumpati.jobmatch.model.Experience;
import com.orumpati.jobmatch.model.Project;
import com.orumpati.jobmatch.model.SkillCategory;
import com.orumpati.jobmatch.resume.AutoTailorService;
import com.orumpati.jobmatch.text.TextUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Free, no-API-key résumé tailoring via the "paste path":
 *  1. /prompt  — builds a strict prompt (master résumé text + JD) the user pastes
 *                into their own Claude/ChatGPT Pro session.
 *  2. /import  — validates the structured JSON the model returns, maps it to a
 *                Profile, and scores it against the JD so the user sees the ATS number
 *                before compiling the PDF (via the existing /api/resume/pdf endpoint).
 *
 * No fabrication is requested: the prompt forbids inventing skills/employers/metrics —
 * it only reorders, rephrases real content toward the JD, and surfaces real keywords.
 */
@RestController
@RequestMapping("/api/resume/tailor")
public class TailorController {

    private final ObjectMapper mapper;
    private final AtsScorer ats;
    private final AutoTailorService autoTailor;

    public TailorController(ObjectMapper mapper, AtsScorer ats, AutoTailorService autoTailor) {
        this.mapper = mapper;
        this.ats = ats;
        this.autoTailor = autoTailor;
    }

    public record PromptRequest(Profile profile, String jdText, String jdTitle) {}

    @PostMapping("/prompt")
    public Map<String, String> prompt(@RequestBody PromptRequest req) {
        return Map.of("prompt", buildPrompt(req.profile(), req.jdText(), req.jdTitle()));
    }

    /** Deterministic, no-LLM "recommended résumé": the website tailors the master
     *  profile to the JD itself (reorder + honest per-section skill lines + conditional
     *  ML-project drop), then scores it — same response shape as /import so the UI can
     *  show the score and download the PDF/.tex with no paste step. */
    @PostMapping("/auto")
    public ResponseEntity<?> autoTailor(@RequestBody PromptRequest req) {
        if (req.profile() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No résumé profile provided — upload one in tab 1 first."));
        }
        Profile tailored = autoTailor.tailor(req.profile(), req.jdText(), req.jdTitle());
        return ResponseEntity.ok(scoreTailored(tailored, req.jdText(), req.jdTitle()));
    }

    public record ImportRequest(String json, String jdText, String jdTitle, Profile master) {}

    @PostMapping("/import")
    public ResponseEntity<?> importTailored(@RequestBody ImportRequest req) {
        String raw = extractJson(req.json());
        if (raw == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "No JSON object found in the pasted text. Paste the full { ... } the model returned."));
        }
        Profile tailored;
        try {
            tailored = mapper.readValue(raw, Profile.class);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            return ResponseEntity.badRequest().body(Map.of("error",
                    "That JSON didn't match the résumé schema: " + msg.split("\\n")[0]));
        }
        // carry over identity + raw text from the master so scoring/contact stay intact
        if (req.master() != null) {
            Profile m = req.master();
            if (isBlank(tailored.getName())) tailored.setName(m.getName());
            if (isBlank(tailored.getEmail())) tailored.setEmail(m.getEmail());
            if (isBlank(tailored.getPhone())) tailored.setPhone(m.getPhone());
            if (tailored.getLinks() == null || tailored.getLinks().isEmpty()) tailored.setLinks(m.getLinks());
            if (isBlank(tailored.getRawText())) tailored.setRawText(m.getRawText());
            if (tailored.getEducation() == null || tailored.getEducation().isEmpty()) tailored.setEducation(m.getEducation());
            tailored.setExperienceYears(m.getExperienceYears());
        }
        if (req.jdTitle() != null && !req.jdTitle().isBlank()) {
            tailored.setTargetRoles(List.of(req.jdTitle().toLowerCase()));
        }
        return ResponseEntity.ok(scoreTailored(tailored, req.jdText(), req.jdTitle()));
    }

    /** Enrich the tailored profile for scoring, score it vs the JD, and shape the
     *  response. Shared by the paste path (/import) and the auto path (/auto). */
    private Map<String, Object> scoreTailored(Profile tailored, String jdText, String jdTitle) {
        // The ATS scorer reads the flat skills/tools lists + rawText. The tailored profile
        // carries skills inside skillCategories, project tech, and per-section skills —
        // index them so scoring SEES them (derived from the résumé's own content, not faked).
        enrichForScoring(tailored);

        AtsResult score = ats.scoreResumeVsJd(tailored, jdText == null ? "" : jdText, jdTitle);
        return Map.of(
                "profile", tailored,
                "score", score.score(),
                "band", score.band(),
                "matched", score.matchedSkills(),
                "missingSkills", score.missingSkills(),
                "missingKeywords", score.missingKeywords());
    }

    /**
     * Flatten every place a real skill can hide (categories, project tech, per-role/
     * per-project skills, bullets) into the flat skills/tools/domains the ATS scorer
     * reads, and into rawText for the keyword component. Uses the same detectors the
     * JD is parsed with, so a genuine match scores honestly — no invented skills.
     */
    private void enrichForScoring(Profile p) {
        StringBuilder t = new StringBuilder();
        t.append(nz(p.getSummary())).append("\n");
        if (p.getSkillCategories() != null)
            for (SkillCategory c : p.getSkillCategories())
                if (c.getItems() != null) t.append(String.join(", ", c.getItems())).append("\n");
        if (p.getExperience() != null)
            for (Experience e : p.getExperience()) {
                if (e.getBullets() != null) e.getBullets().forEach(b -> t.append(b).append("\n"));
                if (e.getSkills() != null) t.append(String.join(", ", e.getSkills())).append("\n");
            }
        if (p.getProjects() != null)
            for (Project pr : p.getProjects()) {
                t.append(nz(pr.getTech())).append("\n");
                if (pr.getBullets() != null) pr.getBullets().forEach(b -> t.append(b).append("\n"));
                if (pr.getSkills() != null) t.append(String.join(", ", pr.getSkills())).append("\n");
            }
        if (p.getAdditional() != null) p.getAdditional().forEach(a -> t.append(a).append("\n"));
        String full = t.toString();

        p.setSkills(mergeDetected(p.getSkills(), TextUtils.detectSkills(full)));
        p.setTools(mergeDetected(p.getTools(), TextUtils.detectTools(full)));
        p.setDomains(mergeDetected(p.getDomains(), TextUtils.detectDomains(full)));
        // rich rawText so the keyword + formatting components see the whole résumé
        String existing = p.getRawText() == null ? "" : p.getRawText();
        p.setRawText(existing + "\n" + full + "\nexperience education skills projects");
    }

    private List<String> mergeDetected(List<String> base, Set<String> detected) {
        Set<String> out = new TreeSet<>(base == null ? List.of() : base);
        out.addAll(detected);
        return new ArrayList<>(out);
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    /* ---------------- prompt text ---------------- */

    private String buildPrompt(Profile p, String jdText, String jdTitle) {
        String master = p != null && p.getRawText() != null && !p.getRawText().isBlank()
                ? p.getRawText() : structuredFallback(p);
        String linksKnown = p != null && p.getLinks() != null && !p.getLinks().isEmpty()
                ? p.getLinks().toString() : "(none parsed — keep any links visible in the résumé text)";
        String title = jdTitle == null || jdTitle.isBlank() ? "(infer the role from the JD)" : jdTitle;

        return """
                You are an expert technical résumé writer and ATS optimization specialist.
                Tailor the candidate's MASTER RÉSUMÉ to the JOB DESCRIPTION below and return
                ONE JSON object in the EXACT schema given. Output JSON only — no prose, no code fences.

                ====================  HARD RULES (do not break)  ====================
                1. NEVER invent skills, employers, titles, dates, metrics, or projects. Use ONLY
                   facts present in the master résumé. Tailoring = REORDER + REPHRASE real content
                   toward the JD's language + SURFACE real keywords the candidate already has.
                2. Professional summary: WRITE A NEW one aimed at this JD. 3–4 sentences. ATS-friendly
                   (plain text, JD keywords the candidate genuinely has). DO NOT mention any project by name.
                3. Skills: keep the candidate's OWN category names (e.g. Languages, Web / Backend,
                   Databases & Cloud, AI, Developer Tools, Core CS). Within each category, put the
                   items the JD asks for FIRST. Order the categories so the most JD-relevant category
                   is first. Keep every real skill; do not add fake ones.
                4. Experience: include EVERY role from the master résumé — never drop, merge, or omit
                   one, even if it looks less relevant. Keep each real role/company/date/location and
                   its links. REWRITE the bullets to foreground JD-relevant work using the JD's
                   terminology, keeping the facts and numbers true. Add a "skills" array per role = the
                   real, JD-relevant skills actually used in that specific role (e.g. the data/NLP/ETL
                   role's skills must reflect that role's own work, the other role's skills must reflect
                   that role's own work — do not mix one role's skills into another's).
                5. Projects: same as experience — keep real name/tech/date/links, rewrite bullets toward
                   the JD, add a real "skills" array. Order projects most-JD-relevant first. NEVER create
                   a project entry for, or duplicate the work of, a company/employer that already appears
                   in Experience — that work belongs ONLY under Experience, once.
                6. Additional: keep real items, order the most JD-relevant first.
                7. Preserve ALL links: top-level linkedin/github/portfolio, and per-role / per-project
                   live + github links if the résumé has them.
                8. Aim for an ATS keyword match of 80+ WITHOUT faking. The scorer does LITERAL keyword
                   matching, not semantic — so wherever the candidate genuinely has the JD's skill, use
                   the JD's EXACT phrase/terminology verbatim (e.g. if the JD says "CI/CD pipelines" and
                   the candidate has that experience, write "CI/CD pipelines", not a paraphrase like
                   "automated build process"). Repeat each genuinely-true top JD keyword in at least two
                   of: summary, skills, bullets — literally, not as a synonym. Never do this for a skill
                   the candidate doesn't actually have.
                9. If the JD is NOT machine-learning / computer-vision / data-science focused, OMIT the
                   "Weed Classification System" project entirely — it's only relevant for ML/CV-focused
                   roles. Include it only when the JD genuinely calls for ML/CV/research skills.

                ====================  OUTPUT SCHEMA (return exactly this shape)  ====================
                {
                  "name": "", "email": "", "phone": "",
                  "links": { "linkedin": "", "github": "", "portfolio": "" },
                  "summary": "JD-tailored, no project names",
                  "skillCategories": [ { "category": "Languages", "items": ["Java","Python"] } ],
                  "experience": [ {
                    "role": "", "company": "", "date": "", "location": "",
                    "links": { "live": "", "github": "" },
                    "bullets": ["rewritten toward the JD, facts unchanged"],
                    "skills": ["real JD-relevant skills used here"]
                  } ],
                  "projects": [ {
                    "name": "", "tech": "", "date": "",
                    "links": { "live": "", "github": "" },
                    "bullets": ["rewritten toward the JD"],
                    "skills": ["real JD-relevant skills"]
                  } ],
                  "education": [ { "school": "", "location": "", "degree": "", "detail": "", "dates": "" } ],
                  "additional": ["most JD-relevant first"]
                }

                ====================  KNOWN LINKS (reuse verbatim)  ====================
                %s

                ====================  TARGET ROLE  ====================
                %s

                ====================  JOB DESCRIPTION  ====================
                %s

                ====================  MASTER RÉSUMÉ  ====================
                %s
                """.formatted(linksKnown, title,
                jdText == null ? "" : jdText.strip(),
                master == null ? "" : master.strip());
    }

    /** If raw text is missing, rebuild a readable résumé from the structured profile. */
    private String structuredFallback(Profile p) {
        if (p == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(p.getName()).append("\n").append(p.getSummary()).append("\n");
        p.getSkillCategories().forEach(c -> sb.append(c.getCategory()).append(": ")
                .append(String.join(", ", c.getItems())).append("\n"));
        p.getExperience().forEach(e -> {
            sb.append(e.getRole()).append(" | ").append(e.getCompany()).append(" ")
                    .append(e.getDate()).append(" ").append(e.getLocation()).append("\n");
            e.getBullets().forEach(b -> sb.append("- ").append(b).append("\n"));
        });
        p.getProjects().forEach(pr -> {
            sb.append(pr.getName()).append(" | ").append(pr.getTech()).append("\n");
            pr.getBullets().forEach(b -> sb.append("- ").append(b).append("\n"));
        });
        p.getEducation().forEach(ed -> sb.append(ed.getSchool()).append(" — ")
                .append(ed.getDegree()).append(" ").append(ed.getDates()).append("\n"));
        p.getAdditional().forEach(a -> sb.append("- ").append(a).append("\n"));
        return sb.toString();
    }

    /** Pull the first balanced { ... } JSON object out of arbitrary pasted text. */
    private String extractJson(String text) {
        if (text == null) return null;
        String t = text.strip().replaceFirst("(?is)^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        int start = t.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < t.length(); i++) {
            char c = t.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return t.substring(start, i + 1);
        }
        return null;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
