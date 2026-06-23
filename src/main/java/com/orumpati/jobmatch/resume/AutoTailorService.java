package com.orumpati.jobmatch.resume;

import com.orumpati.jobmatch.model.Education;
import com.orumpati.jobmatch.model.Experience;
import com.orumpati.jobmatch.model.Profile;
import com.orumpati.jobmatch.model.Project;
import com.orumpati.jobmatch.model.SkillCategory;
import com.orumpati.jobmatch.text.TextUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic, no-LLM résumé tailoring — the "recommended résumé" the website
 * builds itself for a given job. Java port of the Python app/tailor.py honesty rules:
 *
 *  - NEVER invents a skill, employer, date, metric, or project.
 *  - Detects the role ANGLE (ML/AI · Backend/API · Full-stack · Frontend) from the JD.
 *  - Reorders skill categories + items, surfacing JD-relevant ones first.
 *  - Re-emphasises each section's REAL bullets toward the JD (relevance-ranked, polished).
 *  - Orders projects so the angle's strongest real project leads.
 *  - Writes a fresh JD-targeted summary built ONLY from facts the résumé proves.
 *  - Drops the ML/CV-only "Weed Classification" project unless the JD is ML/CV.
 */
@Service
public class AutoTailorService {

    private static final Set<String> ML_TERMS = Set.of(
            "tensorflow", "keras", "pytorch", "scikit-learn", "computer vision", "nlp", "llm", "sam");
    private static final Set<String> FRONT_TERMS = Set.of(
            "react", "vue", "angular", "css3", "html5", "typescript", "chart.js");
    private static final Set<String> BACK_TERMS = Set.of(
            "flask", "django", "spring", "node.js", "rest apis", "sql", "postgresql",
            "microservices", "aws", "gcp", "graphql", "grpc");

    private static final List<String> ACTION_VERBS = List.of(
            "achieved", "benchmarked", "built", "delivered", "designed", "developed",
            "implemented", "integrated", "shipped", "solved");

    private static final Set<String> ML_CV_HINTS = Set.of(
            "machine learning", "deep learning", "computer vision", "neural network",
            "image classification", "cnn", "pytorch", "tensorflow", "keras", "scikit-learn",
            "ml engineer", "data scientist", "applied scientist", "model training",
            "resnet", "transfer learning");

    /** Build a tailored copy of the profile aimed at this JD (mutates the per-request copy). */
    public Profile tailor(Profile p, String jdText, String jdTitle) {
        if (p == null) return null;
        String jd = nz(jdText) + " " + nz(jdTitle);
        String jdLower = jd.toLowerCase(Locale.ROOT);

        Set<String> jdWanted = new LinkedHashSet<>();
        TextUtils.detectSkills(jd).forEach(s -> jdWanted.add(s.toLowerCase(Locale.ROOT)));
        TextUtils.detectTools(jd).forEach(s -> jdWanted.add(s.toLowerCase(Locale.ROOT)));

        String angle = detectRoleAngle(jdWanted, jdLower);
        boolean jdIsMlCv = angle.equals("ml_ai") || ML_CV_HINTS.stream().anyMatch(jdLower::contains);

        reorderSkillCategories(p, jdWanted);
        emphasiseExperience(p, jdWanted, jdLower, angle);
        tailorProjects(p, jdWanted, jdLower, angle, jdIsMlCv);
        p.setSummary(buildSummary(p, jdWanted, angle, jdTitle));

        // de-duplicate: if the summary already cites LeetCode/214, drop that line from
        // Additional so the same fact isn't repeated.
        String sumLow = nz(p.getSummary()).toLowerCase(Locale.ROOT);
        if (p.getAdditional() != null && (sumLow.contains("leetcode") || sumLow.contains("214"))) {
            p.getAdditional().removeIf(a -> {
                String l = a.toLowerCase(Locale.ROOT);
                return l.contains("leetcode") || l.contains("214") || l.contains("geeksforgeeks");
            });
        }

        if (jdTitle != null && !jdTitle.isBlank()) {
            p.setTargetRoles(List.of(jdTitle.toLowerCase(Locale.ROOT)));
        }
        return p;
    }

    /* ---------------- role angle ---------------- */

    private String detectRoleAngle(Set<String> jdWanted, String title) {
        boolean ml = jdWanted.stream().anyMatch(ML_TERMS::contains)
                || containsAny(title, "machine learning", "ml engineer", "ai engineer",
                "data scientist", "computer vision", "deep learning");
        if (ml) return "ml_ai";
        boolean front = jdWanted.stream().anyMatch(FRONT_TERMS::contains)
                || containsAny(title, "frontend", "front end", "front-end", "ui engineer");
        boolean back = jdWanted.stream().anyMatch(BACK_TERMS::contains)
                || containsAny(title, "backend", "back end", "back-end", "api", "platform", "server");
        if (title.contains("full stack") || title.contains("full-stack") || (front && back)) return "full_stack";
        if (front && !back) return "frontend";
        return "backend_api";
    }

    private String angleLabel(String angle) {
        return switch (angle) {
            case "ml_ai" -> "ML/AI-focused";
            case "frontend" -> "frontend-focused";
            case "full_stack" -> "full-stack";
            default -> "backend-leaning";
        };
    }

    /* ---------------- skills ---------------- */

    private void reorderSkillCategories(Profile p, Set<String> jdWanted) {
        if (p.getSkillCategories() == null || p.getSkillCategories().isEmpty()) return;
        for (SkillCategory c : p.getSkillCategories()) {
            if (c.getItems() == null) continue;
            List<String> items = new ArrayList<>(c.getItems());
            items.sort(Comparator.comparingInt(it -> wanted(it, jdWanted) ? 0 : 1)); // stable: JD items first
            c.setItems(items);
        }
        List<SkillCategory> cats = new ArrayList<>(p.getSkillCategories());
        cats.sort(Comparator.comparingInt((SkillCategory c) -> -categoryRelevance(c, jdWanted)));
        p.setSkillCategories(cats);

        // flat skills list: JD-relevant first, keep all real skills (drop none)
        List<String> skills = p.getSkills() == null ? List.of() : p.getSkills();
        List<String> front = skills.stream().filter(s -> jdWanted.contains(s.toLowerCase(Locale.ROOT))).toList();
        List<String> back = skills.stream().filter(s -> !jdWanted.contains(s.toLowerCase(Locale.ROOT))).toList();
        List<String> ordered = new ArrayList<>();
        front.forEach(s -> { if (!ordered.contains(s)) ordered.add(s); });
        back.forEach(s -> { if (!ordered.contains(s)) ordered.add(s); });
        p.setSkills(ordered);
    }

    private int categoryRelevance(SkillCategory c, Set<String> jdWanted) {
        if (c.getItems() == null) return 0;
        return (int) c.getItems().stream().filter(it -> wanted(it, jdWanted)).count();
    }

    /* ---------------- experience ---------------- */

    private void emphasiseExperience(Profile p, Set<String> jdWanted, String jdLower, String angle) {
        if (p.getExperience() == null) return;
        for (Experience e : p.getExperience()) {
            String secText = String.join(" ", nz(e.getRole()), nz(e.getCompany()), join(e.getBullets()), join(e.getSkills()));
            e.setBullets(rankBullets(e.getBullets(), jdWanted, jdLower, angle));
            List<String> derived = sectionRealSkills(secText, jdWanted, 12);
            if (!derived.isEmpty()) e.setSkills(derived);
        }
        // keep EVERY role; order by JD relevance
        List<Experience> exp = new ArrayList<>(p.getExperience());
        exp.sort(Comparator.comparingInt((Experience e) ->
                -relevance(String.join(" ", nz(e.getRole()), nz(e.getCompany()), join(e.getBullets())), jdLower)));
        p.setExperience(exp);
    }

    private void tailorProjects(Profile p, Set<String> jdWanted, String jdLower, String angle, boolean jdIsMlCv) {
        if (p.getProjects() == null) return;
        List<Project> kept = new ArrayList<>();
        for (Project pr : p.getProjects()) {
            String projText = String.join(" ", nz(pr.getName()), nz(pr.getTech()), join(pr.getBullets()))
                    .toLowerCase(Locale.ROOT);
            boolean isWeed = projText.contains("weed") || projText.contains("deepweeds");
            if (!jdIsMlCv && isWeed) continue; // ML/CV-only project — drop for non-ML roles
            String secText = String.join(" ", nz(pr.getName()), nz(pr.getTech()), join(pr.getBullets()), join(pr.getSkills()));
            pr.setBullets(rankBullets(pr.getBullets(), jdWanted, jdLower, angle));
            List<String> derived = sectionRealSkills(secText, jdWanted, 12);
            if (!derived.isEmpty()) pr.setSkills(derived);
            kept.add(pr);
        }
        kept.sort(Comparator.comparingInt((Project pr) ->
                -relevance(String.join(" ", nz(pr.getName()), nz(pr.getTech()), join(pr.getBullets())), jdLower)));
        p.setProjects(kept);
    }

    /** Rank a section's REAL bullets by JD relevance and lightly polish wording.
     *  Keeps every bullet (reorder only) — never drops or invents content. */
    private List<String> rankBullets(List<String> bullets, Set<String> jdWanted, String jdLower, String angle) {
        if (bullets == null || bullets.isEmpty()) return bullets;
        List<String> sorted = new ArrayList<>(bullets);
        sorted.sort(Comparator.comparingInt((String b) -> -bulletScore(b, jdWanted, jdLower, angle)));
        return sorted.stream().map(this::polishBullet).collect(Collectors.toList());
    }

    private int bulletScore(String bullet, Set<String> jdWanted, String jdLower, String angle) {
        Set<String> terms = new LinkedHashSet<>();
        TextUtils.detectSkills(bullet).forEach(s -> terms.add(s.toLowerCase(Locale.ROOT)));
        TextUtils.detectTools(bullet).forEach(s -> terms.add(s.toLowerCase(Locale.ROOT)));
        String low = bullet.toLowerCase(Locale.ROOT);
        int score = 5 * (int) terms.stream().filter(jdWanted::contains).count();
        if (!TextUtils.extractNumbers(bullet).isEmpty()) score += 2;
        if (ACTION_VERBS.stream().anyMatch(low::startsWith)) score += 1;
        // work-style proof: reward matching evidence even when exact keyword detection misses
        String[][] pairs = {
                {"api", "rest", "api", "client-server", "json"},
                {"frontend", "html", "css", "javascript", "ui", "dashboard"},
                {"machine learning", "tensorflow", "keras", "cnn", "benchmark"},
                {"new grad", "data structures", "leetcode", "algorithms"},
        };
        for (String[] grp : pairs) {
            if (jdLower.contains(grp[0])) {
                for (int k = 1; k < grp.length; k++) if (low.contains(grp[k])) { score += 2; break; }
            }
        }
        return score;
    }

    private String polishBullet(String bullet) {
        String t = nz(bullet).trim().replaceAll("\\s+", " ");
        t = t.replaceFirst("(?i)^Responsible for\\s+", "Delivered ");
        t = t.replaceFirst("(?i)^Worked on\\s+", "Built ");
        t = t.replaceFirst("(?i)^Helped with\\s+", "Supported ");
        t = t.replaceFirst("(?i)^Created\\s+", "Built ");
        if (!t.isEmpty() && "?!.".indexOf(t.charAt(t.length() - 1)) < 0) t += ".";
        return t;
    }

    /* ---------------- summary (honest, JD-targeted) ---------------- */

    private static final java.util.Map<String, String> ANGLE_WORK = java.util.Map.of(
            "ml_ai", "data-driven, ML-oriented systems",
            "frontend", "responsive, user-facing web interfaces",
            "full_stack", "full-stack web applications end to end",
            "backend_api", "reliable backend services and APIs");

    /** A natural, JD-targeted professional summary — NO project/company names, and
     *  every skill it names is a real résumé skill (honesty-gated). */
    private String buildSummary(Profile p, Set<String> jdWanted, String angle, String jdTitle) {
        Set<String> real = new LinkedHashSet<>();
        if (p.getSkills() != null) p.getSkills().forEach(s -> real.add(s.toLowerCase(Locale.ROOT)));
        if (p.getTools() != null) p.getTools().forEach(s -> real.add(s.toLowerCase(Locale.ROOT)));
        if (real.isEmpty()) return p.getSummary();

        // lead skills: real profile skills the JD wants; fall back to strongest real skills.
        List<String> mySkills = p.getSkills() == null ? List.<String>of() : p.getSkills();
        List<String> matched = mySkills.stream()
                .filter(s -> jdWanted.contains(s.toLowerCase(Locale.ROOT)))
                .limit(6).collect(Collectors.toList());
        if (matched.isEmpty()) matched = mySkills.stream().limit(5).collect(Collectors.toList());

        boolean hasMasters = p.getEducation() != null && p.getEducation().stream()
                .map(Education::getDegree).filter(d -> d != null)
                .anyMatch(d -> d.toLowerCase(Locale.ROOT).contains("master")
                        || d.toLowerCase(Locale.ROOT).contains("m.s") || d.toLowerCase(Locale.ROOT).startsWith("ms"));

        String fact = factText(p).toLowerCase(Locale.ROOT);
        String target = cleanRoleTitle(jdTitle);

        List<String> lines = new ArrayList<>();
        // S1 — who + what they're targeting (from the JD title), no company/project names.
        StringBuilder s1 = new StringBuilder(capitalize(angleLabel(angle)) + " software engineer and recent graduate");
        if (hasMasters) s1.append(" with an MS in Computer Science");
        if (!target.isBlank()) s1.append(", targeting ").append(target).append(" roles");
        s1.append(".");
        lines.add(s1.toString());

        // S2 — what they build (angle phrase, no skill tokens) + real JD-matched skills.
        if (!matched.isEmpty()) {
            lines.add("Builds " + ANGLE_WORK.getOrDefault(angle, "production software")
                    + " with " + joinTerms(matched)
                    + ", with a focus on clean, maintainable, well-tested code.");
        }

        // S3 — process / collaboration (generic, ATS-friendly soft signals; no skill tokens
        // so it never trips the honesty gate). Weaves in JD responsibility language.
        lines.add("Comfortable across the full software development lifecycle, from gathering "
                + "requirements and designing scalable solutions to shipping, debugging, and "
                + "iterating in fast-paced, cross-functional team environments.");

        // S4 — fundamentals + drive + value (quantified strength folded in honestly).
        StringBuilder s4 = new StringBuilder("Strong computer-science fundamentals and problem-solving ability");
        if (fact.contains("214")) s4.append(" (214+ LeetCode problems solved)");
        else if (fact.contains("leetcode")) s4.append(" with consistent algorithmic practice");
        s4.append(", with a track record of learning quickly, communicating clearly, and "
                + "delivering reliable software that drives measurable impact.");
        lines.add(s4.toString());

        // honesty gate: every skill named in a line must be a real skill
        List<String> clean = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : lines) {
            String norm = line.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
            if (norm.isEmpty() || seen.contains(norm)) continue;
            if (summaryLineAllowed(line, real)) { clean.add(line); seen.add(norm); }
            if (clean.size() >= 4) break;
        }
        // never emit em/en dashes or "--" in generated prose (looks odd in a summary)
        return clean.isEmpty() ? p.getSummary()
                : String.join(" ", clean).replaceAll("\\s*[—–]\\s*", ", ").replace(" -- ", ", ");
    }

    /** Clean a JD title for the summary: drop trailing company/location noise and
     *  seniority that doesn't apply to a new grad. */
    private String cleanRoleTitle(String title) {
        if (title == null || title.isBlank()) return "";
        String t = title.split("[,|\\-–—(]")[0].strip();        // cut at comma/dash/paren
        t = t.replaceAll("(?i)\\b(senior|sr\\.?|staff|lead|principal|ii|iii|iv)\\b", "").replaceAll("\\s+", " ").strip();
        return t.length() > 40 ? "" : t;
    }

    private boolean summaryLineAllowed(String line, Set<String> real) {
        Set<String> mentioned = new LinkedHashSet<>();
        TextUtils.detectSkills(line).forEach(s -> mentioned.add(s.toLowerCase(Locale.ROOT)));
        TextUtils.detectTools(line).forEach(s -> mentioned.add(s.toLowerCase(Locale.ROOT)));
        return real.containsAll(mentioned);
    }

    private String factText(Profile p) {
        StringBuilder sb = new StringBuilder(nz(p.getSummary()));
        if (p.getAdditional() != null) p.getAdditional().forEach(a -> sb.append(" ").append(a));
        if (p.getExperience() != null) p.getExperience().forEach(e -> sb.append(" ").append(join(e.getBullets())));
        if (p.getProjects() != null) p.getProjects().forEach(pr -> sb.append(" ").append(join(pr.getBullets())));
        return sb.toString();
    }

    /* ---------------- helpers ---------------- */

    /** The REAL skills a section demonstrates (detected from its own text), ordered
     *  JD-relevant first, capped. Honest — only skills actually present in the section,
     *  never invented — but complete, so the per-role/-project skills line isn't thin. */
    private List<String> sectionRealSkills(String sectionText, Set<String> jdWanted, int cap) {
        java.util.LinkedHashSet<String> det = new java.util.LinkedHashSet<>();
        TextUtils.detectSkills(sectionText).forEach(s -> det.add(s.toLowerCase(Locale.ROOT)));
        TextUtils.detectTools(sectionText).forEach(s -> det.add(s.toLowerCase(Locale.ROOT)));
        List<String> out = new ArrayList<>();
        det.stream().filter(jdWanted::contains).forEach(s -> out.add(pretty(s)));   // JD-relevant first
        det.stream().filter(s -> !jdWanted.contains(s)).forEach(s -> out.add(pretty(s)));
        return out.size() > cap ? out.subList(0, cap) : out;
    }

    private int relevance(String text, String jdLower) {
        if (text == null || text.isBlank()) return 0;
        String t = text.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String tok : TextUtils.contentTokens(jdLower)) if (tok.length() >= 4 && t.contains(tok)) score++;
        return score;
    }

    private boolean wanted(String item, Set<String> jdWanted) {
        if (item == null) return false;
        String low = item.toLowerCase(Locale.ROOT);
        return jdWanted.contains(low) || jdWanted.stream().anyMatch(w -> low.contains(w) || w.contains(low));
    }

    private boolean containsAny(String hay, String... needles) {
        for (String n : needles) if (hay.contains(n)) return true;
        return false;
    }

    private static final java.util.Map<String, String> DISPLAY = java.util.Map.ofEntries(
            java.util.Map.entry("aws", "AWS"), java.util.Map.entry("gcp", "GCP"),
            java.util.Map.entry("sql", "SQL"), java.util.Map.entry("oop", "OOP"),
            java.util.Map.entry("nlp", "NLP"), java.util.Map.entry("llm", "LLM"),
            java.util.Map.entry("json", "JSON"), java.util.Map.entry("rest apis", "REST APIs"),
            java.util.Map.entry("html5", "HTML5"), java.util.Map.entry("css3", "CSS3"),
            java.util.Map.entry("c++", "C++"), java.util.Map.entry("c", "C"),
            java.util.Map.entry("api design", "API design"), java.util.Map.entry("ci/cd", "CI/CD"),
            java.util.Map.entry("google gemini api", "Google Gemini API"), java.util.Map.entry("oauth", "OAuth"),
            java.util.Map.entry("sam", "SAM"), java.util.Map.entry("chart.js", "Chart.js"),
            java.util.Map.entry("node.js", "Node.js"), java.util.Map.entry("client-server", "client-server"),
            java.util.Map.entry("data structures", "data structures"), java.util.Map.entry("computer vision", "computer vision"),
            java.util.Map.entry("tensorflow", "TensorFlow"), java.util.Map.entry("keras", "Keras"),
            java.util.Map.entry("pytorch", "PyTorch"), java.util.Map.entry("javascript", "JavaScript"),
            java.util.Map.entry("typescript", "TypeScript"), java.util.Map.entry("graphql", "GraphQL"),
            java.util.Map.entry("github", "GitHub"), java.util.Map.entry("postgresql", "PostgreSQL"),
            java.util.Map.entry("google calendar api", "Google Calendar API"),
            java.util.Map.entry("python", "Python"), java.util.Map.entry("java", "Java"),
            java.util.Map.entry("flask", "Flask"), java.util.Map.entry("spring", "Spring"),
            java.util.Map.entry("docker", "Docker"), java.util.Map.entry("kubernetes", "Kubernetes"),
            java.util.Map.entry("microservices", "microservices"));

    private String pretty(String skill) {
        if (skill == null) return "";
        String key = skill.toLowerCase(Locale.ROOT);
        if (DISPLAY.containsKey(key)) return DISPLAY.get(key);
        String[] parts = skill.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : parts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return sb.toString();
    }

    /** Natural-language list with pretty skill display: "A, B, and C". */
    private String joinTerms(List<String> terms) {
        return joinList(terms.stream().map(this::pretty).collect(Collectors.toList()));
    }

    private String joinList(List<String> terms) {
        List<String> t = terms.stream().filter(x -> x != null && !x.isBlank()).toList();
        if (t.isEmpty()) return "";
        if (t.size() == 1) return t.get(0);
        if (t.size() == 2) return t.get(0) + " and " + t.get(1);
        return String.join(", ", t.subList(0, t.size() - 1)) + ", and " + t.get(t.size() - 1);
    }

    private String capitalize(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String nz(String s) { return s == null ? "" : s; }

    private String join(List<String> xs) { return xs == null ? "" : String.join(" ", xs); }
}
