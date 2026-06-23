package com.orumpati.jobmatch.roles;

import java.util.*;
import java.util.regex.Pattern;

/** Java port of app/roles.py — role-focus / specialization model. */
public final class Roles {

    public static final List<String> GENERIC_SWE = List.of(
            "software engineer", "software developer", "software development engineer",
            "software development", "sde", "swe", "programmer", "software engineering",
            "applications engineer", "application developer");

    public static final Map<String, RoleFocus> ROLE_FOCUS = new LinkedHashMap<>();

    static {
        put("newgrad_swe", "New Grad Software Engineer",
                List.of("new grad software engineer", "software engineer new grad",
                        "new graduate software engineer", "new grad swe", "swe new grad"),
                List.of("new grad software engineer"),
                List.of("python", "java", "javascript", "sql", "rest apis", "data structures", "algorithms", "oop", "git"));
        put("entry_swe", "Entry Level Software Engineer",
                List.of("entry level software engineer", "entry-level software engineer",
                        "entry level swe", "entry-level swe"),
                List.of("entry level software engineer"),
                List.of("python", "java", "javascript", "sql", "rest apis", "data structures", "algorithms", "oop", "git"));
        put("junior_dev", "Junior Software Developer",
                List.of("junior software developer", "junior software engineer", "junior developer"),
                List.of("junior software developer"),
                List.of("python", "java", "javascript", "sql", "rest apis", "html5", "css3", "git"));
        put("associate_swe", "Associate Software Engineer",
                List.of("associate software engineer", "associate software developer", "associate swe"),
                List.of("associate software engineer"),
                List.of("python", "java", "javascript", "sql", "rest apis", "data structures", "algorithms", "oop", "git"));
        put("swe_i", "Software Engineer I",
                List.of("software engineer i", "software engineer 1", "sde i", "sde 1",
                        "software developer i", "software developer 1"),
                List.of("software engineer i"),
                List.of("python", "java", "javascript", "sql", "rest apis", "data structures", "algorithms", "oop", "git"));
        put("new_college_grad_swe", "New College Grad SWE",
                List.of("new college grad software engineer", "new college graduate software engineer",
                        "new college grad swe", "new college graduate swe", "ncg software engineer"),
                List.of("new college grad software engineer"),
                List.of("python", "java", "javascript", "sql", "rest apis", "data structures", "algorithms", "oop", "git"));
        put("newgrad", "New Grad",
                List.of("new grad", "new graduate", "university graduate", "university grad",
                        "entry level", "entry-level", "early career", "campus", "graduate",
                        "associate software engineer", "software engineer i", "software engineer 1",
                        "software developer i", "software developer 1", "sde i", "sde 1",
                        "junior software engineer", "junior software developer", "associate software developer"),
                List.of("software engineer new grad", "new graduate software engineer",
                        "entry level software engineer", "associate software engineer",
                        "junior software developer", "junior software engineer",
                        "entry level software developer", "software engineer i", "software development engineer i"),
                List.of("python", "java", "javascript", "sql", "rest apis", "data structures", "algorithms", "oop", "git"));
        put("backend", "Backend",
                List.of("backend", "back end", "back-end", "server", "server-side",
                        "api engineer", "services engineer", "distributed systems"),
                List.of("backend engineer", "backend developer", "software engineer", "server engineer", "api engineer"),
                List.of("python", "java", "sql", "rest apis", "spring", "node.js", "microservices", "postgresql", "aws", "docker"));
        put("frontend", "Frontend",
                List.of("frontend", "front end", "front-end", "front-end engineer",
                        "ui engineer", "ui/ux engineer", "web developer", "react developer", "javascript engineer"),
                List.of("frontend engineer", "front end engineer", "ui engineer", "web developer", "software engineer"),
                List.of("javascript", "typescript", "react", "html5", "css3", "vue", "angular", "rest apis"));
        put("fullstack", "Full-stack",
                List.of("full stack", "full-stack", "fullstack", "web application engineer"),
                List.of("full stack engineer", "full-stack engineer", "software engineer", "web developer"),
                List.of("javascript", "typescript", "react", "node.js", "python", "java", "sql", "rest apis", "html5", "css3"));
        put("mlai", "ML / AI",
                List.of("machine learning", "ml engineer", "ai engineer", "applied scientist",
                        "deep learning", "data scientist", "mlops", "ai/ml", "research engineer"),
                List.of("machine learning engineer", "ai engineer", "ml engineer",
                        "software engineer, machine learning", "applied scientist"),
                List.of("python", "tensorflow", "pytorch", "keras", "nlp", "computer vision", "scikit-learn", "pandas", "numpy"));
        put("data", "Data",
                List.of("data engineer", "data engineering", "etl", "analytics engineer", "data platform", "data infrastructure"),
                List.of("data engineer", "analytics engineer", "software engineer, data"),
                List.of("python", "sql", "postgresql", "kafka", "aws", "spark"));
        put("mobile", "Mobile",
                List.of("mobile engineer", "mobile developer", "ios", "android", "react native", "flutter"),
                List.of("mobile engineer", "ios engineer", "android engineer", "software engineer, mobile"),
                List.of("swift", "kotlin", "java", "react", "javascript"));
        put("devops", "DevOps / Platform",
                List.of("devops", "site reliability", "sre", "platform engineer", "infrastructure engineer", "cloud engineer"),
                List.of("devops engineer", "site reliability engineer", "platform engineer", "cloud engineer", "software engineer, infrastructure"),
                List.of("aws", "gcp", "azure", "docker", "kubernetes", "terraform", "ci/cd", "linux", "python"));
        put("general", "General SWE",
                List.of("software engineer", "software developer", "sde", "backend", "full stack",
                        "full-stack", "software development", "frontend", "front end", "front-end"),
                List.of("software engineer", "software developer"),
                List.of());
    }

    private static void put(String key, String label, List<String> hints, List<String> targetRoles, List<String> coreSkills) {
        ROLE_FOCUS.put(key, new RoleFocus(key, label, hints, targetRoles, coreSkills));
    }

    public static final List<String> HARD_REJECT = List.of(
            "quality assurance", "qa engineer", "qa analyst", "quality engineer",
            "test engineer", "validation engineer", "semiconductor", "data entry",
            "help desk", "helpdesk", "service desk", "desktop support", "it support",
            "technical support", "tech support", "business analyst", "business systems analyst",
            "sales engineer", "solutions engineer", "pre-sales", "presales",
            "account executive", "account manager", "recruiter", "talent acquisition",
            "marketing", "accountant", "bookkeeper", "financial analyst",
            "mechanical engineer", "electrical engineer", "civil engineer",
            "industrial engineer", "manufacturing engineer", "process engineer",
            "chemical engineer", "biomedical engineer", "field engineer", "field service",
            "service technician", "maintenance technician", "customer success",
            "customer support", "customer service", "scrum master", "project manager",
            "program manager", "product manager", "office", "administrative", "warehouse",
            "driver", "nurse", "teacher", "consultant");

    private static final Set<String> SPECIALIZED = Set.of("backend", "frontend", "fullstack", "mlai", "data", "mobile", "devops");
    private static final Set<String> NEWGRAD_KEYS = Set.of(
            "newgrad_swe", "entry_swe", "junior_dev", "associate_swe",
            "swe_i", "new_college_grad_swe", "newgrad");

    private static final List<String> SWE_NEWGRAD_HINTS = List.of(
            "associate software engineer", "software engineer i", "software engineer 1",
            "software developer i", "software developer 1", "sde i", "sde 1",
            "new grad software", "graduate software engineer", "software engineering",
            "junior software engineer", "junior software developer",
            "associate software developer", "entry level software developer");

    private static final List<String> HARDWARE_HINTS = List.of(
            "robotics", "embedded", "firmware", "fpga", "asic", "verilog", "rtl design",
            "hardware engineer", "device driver", "gpu kernel", "cuda kernel",
            "kernel engineer", "silicon", "control systems",
            "wireless", "rf engineer", "rf software", "radio frequency", "wlan", "baseband",
            "modem", "cellular", "antenna", "ofdma", "telecom", "network", "signal processing");

    private static final Pattern NEWGRAD_TITLE_RE = Pattern.compile(
            "\\b(new[\\s-]?grad(uate)?|recent\\s+grad(uate)?|university\\s+grad(uate)?|"
                    + "college\\s+grad(uate)?|new\\s+college\\s+grad|ncg|early[\\s-]?career|entry[\\s-]?level|"
                    + "campus|rotational|apprentice|associate\\s+(software\\s+)?(engineer|developer)|"
                    + "junior|grad(uate)?\\s+(software\\s+)?(engineer|developer))\\b"
                    + "|\\b(software\\s+engineer|software\\s+developer|sde|sw\\s+engineer)\\s*(i|1)\\b"
                    + "|\\bgrad(uate)?\\s+program\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern NEWGRAD_JD_RE = Pattern.compile(
            "\\b(new[\\s-]?grad(uate)?|recent(ly)?\\s+grad(uate|uated)?|recent\\s+college\\s+grad|"
                    + "new\\s+college\\s+grad|early[\\s-]?career|entry[\\s-]?level|university\\s+grad(uate)?|"
                    + "currently\\s+pursuing|graduating\\s+(in|by|between)|final[\\s-]?year\\s+student|"
                    + "about\\s+to\\s+graduate|students?\\s+and\\s+(new\\s+)?grad|for\\s+new\\s+grad)\\b",
            Pattern.CASE_INSENSITIVE);

    public static boolean isNewGradRole(String title, String content) {
        return (title != null && NEWGRAD_TITLE_RE.matcher(title).find())
                || (content != null && NEWGRAD_JD_RE.matcher(content).find());
    }

    public static boolean requiresNewGradSignal(List<String> focusKeys) {
        return normKeys(focusKeys).stream().anyMatch(NEWGRAD_KEYS::contains);
    }

    private static List<String> normKeys(List<String> focusKeys) {
        if (focusKeys == null || focusKeys.isEmpty()) return List.of("general");
        List<String> keys = focusKeys.stream().filter(ROLE_FOCUS::containsKey).toList();
        return keys.isEmpty() ? List.of("general") : keys;
    }

    public static List<String> labelsFor(List<String> focusKeys) {
        return normKeys(focusKeys).stream().map(k -> ROLE_FOCUS.get(k).label()).toList();
    }

    public static List<String> targetRolesFor(List<String> focusKeys) {
        List<String> out = new ArrayList<>();
        for (String k : normKeys(focusKeys)) {
            for (String r : ROLE_FOCUS.get(k).targetRoles()) {
                if (!out.contains(r)) out.add(r);
            }
        }
        if (!out.contains("software engineer")) out.add("software engineer");
        return out;
    }

    public static List<String> coreSkillsFor(List<String> focusKeys) {
        List<String> out = new ArrayList<>();
        for (String k : normKeys(focusKeys)) {
            for (String s : ROLE_FOCUS.get(k).coreSkills()) {
                if (!out.contains(s)) out.add(s);
            }
        }
        return out;
    }

    private static boolean isGenericSwe(String titleLower) {
        return GENERIC_SWE.stream().anyMatch(titleLower::contains);
    }

    public static boolean titleMatchesFocus(String title, List<String> focusKeys) {
        String tl = title == null ? "" : title.toLowerCase();
        List<String> keys = normKeys(focusKeys);

        if (HARD_REJECT.stream().anyMatch(tl::contains) && !isGenericSwe(tl)) {
            return false;
        }

        if (keys.contains("general")) {
            return ROLE_FOCUS.get("general").hints().stream().anyMatch(tl::contains) || isGenericSwe(tl);
        }

        List<String> selectedSpec = keys.stream().filter(SPECIALIZED::contains).toList();
        List<String> specHints = selectedSpec.stream()
                .flatMap(k -> ROLE_FOCUS.get(k).hints().stream()).toList();
        if (specHints.stream().anyMatch(tl::contains)) return true;

        if (isGenericSwe(tl)) {
            List<String> unselected = SPECIALIZED.stream().filter(k -> !keys.contains(k)).toList();
            List<String> otherHints = unselected.stream()
                    .flatMap(k -> ROLE_FOCUS.get(k).hints().stream()).toList();
            if (otherHints.stream().anyMatch(tl::contains)) return false;
            boolean webFocusOnly = Set.of("mlai", "data", "devops", "general").stream().noneMatch(keys::contains);
            if (webFocusOnly && HARDWARE_HINTS.stream().anyMatch(tl::contains)) return false;
            return true;
        }

        if (keys.contains("newgrad") && SWE_NEWGRAD_HINTS.stream().anyMatch(tl::contains)) {
            return true;
        }

        return false;
    }

    private Roles() {}
}
