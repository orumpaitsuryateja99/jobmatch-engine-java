package com.orumpati.jobmatch.text;

import com.orumpati.jobmatch.skills.SkillsDb;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Java port of app/textutils.py — alias-aware skill/tool detection, JD signal
 * extraction (years required, senior title, sponsorship block, US location,
 * work mode). Pure-Java, no NLP deps, mirrors the Python regexes 1:1. */
public final class TextUtils {

    public static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "if", "then", "else", "for", "to", "of", "in", "on", "at", "by",
            "with", "without", "from", "as", "is", "are", "was", "were", "be", "been", "being", "this", "that",
            "these", "those", "it", "its", "we", "you", "they", "he", "she", "them", "our", "your", "their",
            "will", "would", "shall", "should", "can", "could", "may", "might", "must", "do", "does", "did",
            "done", "have", "has", "had", "not", "no", "yes", "ll", "re", "ve", "s", "t", "job", "role", "work",
            "experience", "years", "year", "team", "teams", "ability", "strong", "excellent", "good", "great",
            "new", "grad", "graduate", "entry", "level", "senior", "junior", "including", "include", "included",
            "etc", "preferred", "required", "requirement", "requirements", "responsibilities", "responsible",
            "qualifications", "qualification", "plus", "candidate", "candidates", "company", "companies",
            "position", "positions", "opportunity", "opportunities", "looking", "seeking", "join", "build",
            "building", "develop", "developing", "design", "designing", "using", "use", "used", "across",
            "within", "able", "help", "support", "who", "what", "when", "where", "which", "while", "about",
            "into", "more", "most", "other", "some", "such", "only", "own", "same", "so", "than", "too", "very");

    private static final Pattern WORD_RE = Pattern.compile("[a-zA-Z][a-zA-Z0-9+#.\\-]*");
    private static final Pattern NUM_RE = Pattern.compile("\\b\\d+(?:[.,]\\d+)?%?\\b");

    private static Pattern compileAliasRegex(Collection<String> aliases) {
        List<String> ordered = new ArrayList<>(aliases);
        ordered.sort((a, b) -> b.length() - a.length());
        String alternation = ordered.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        return Pattern.compile("(?<![a-z0-9])(?:" + alternation + ")(?![a-z0-9])");
    }

    private static final Pattern SKILL_RE = compileAliasRegex(SkillsDb.SKILL_ALIASES.keySet());
    private static final Pattern TOOL_RE = compileAliasRegex(SkillsDb.TOOL_ALIASES.keySet());
    private static final Map<String, String> DOMAIN_KW = SkillsDb.domainKeywordMap();
    private static final Pattern DOMAIN_RE = compileAliasRegex(DOMAIN_KW.keySet());

    private static final Pattern C_CONTEXT_RE = Pattern.compile(
            "\\bc\\s*[/,]\\s*c\\+\\+|\\bc\\+\\+\\s*[/,]\\s*c\\b|\\bc\\s+and\\s+c\\+\\+|\\bc\\+\\+\\s+and\\s+c\\b"
                    + "|\\bembedded\\s+c\\b|\\bansi\\s+c\\b|\\bc\\s+programming\\b|\\bprogramming\\s+in\\s+c\\b"
                    + "|\\bc\\s+language\\b", Pattern.CASE_INSENSITIVE);

    public static String normalize(String text) {
        return text == null ? "" : text.toLowerCase();
    }

    private static Set<String> matchAliases(String textLower, Map<String, String> aliasMap, Pattern combined) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = combined.matcher(textLower);
        while (m.find()) {
            out.add(aliasMap.get(m.group()));
        }
        return out;
    }

    public static Set<String> detectSkills(String text) {
        Set<String> found = matchAliases(normalize(text), SkillsDb.SKILL_ALIASES, SKILL_RE);
        if (!found.contains("c") && text != null && C_CONTEXT_RE.matcher(text).find()) {
            found.add("c");
        }
        return found;
    }

    public static Set<String> detectTools(String text) {
        return matchAliases(normalize(text), SkillsDb.TOOL_ALIASES, TOOL_RE);
    }

    public static Set<String> detectDomains(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = DOMAIN_RE.matcher(normalize(text));
        while (m.find()) {
            out.add(DOMAIN_KW.get(m.group()));
        }
        return out;
    }

    public static Set<String> contentTokens(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        Matcher m = WORD_RE.matcher(text);
        while (m.find()) {
            String t = m.group().toLowerCase();
            if (!STOPWORDS.contains(t) && t.length() > 2) out.add(t);
        }
        return out;
    }

    public static List<String> topKeywords(String text, int n) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (text != null) {
            Matcher m = WORD_RE.matcher(text);
            while (m.find()) {
                String t = m.group().toLowerCase();
                if (!STOPWORDS.contains(t) && t.length() > 2) {
                    counts.merge(t, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(n)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    public static Set<String> extractNumbers(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher m = NUM_RE.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static final Pattern NON_REQUIREMENT_YEARS = Pattern.compile(
            "\\b(history|ago|growth|business|operation|operating|innovation|anniversary|"
                    + "old|founded|building|built|serving|served|trusted|since|established|industry "
                    + "leader|in the (?:market|industry)|track record)\\b");

    private static final Pattern YEARS_RE = Pattern.compile(
            "([^.\\n]{0,24}?)(\\d+)\\s*\\+?\\s*(?:-\\s*\\d+\\s*)?years?\\b([^.\\n]{0,22})");

    public static int extractYearsRequired(String text) {
        String tl = normalize(text).replace("–", "-").replace("—", "-");
        Matcher m = YEARS_RE.matcher(tl);
        List<Integer> out = new ArrayList<>();
        while (m.find()) {
            String pre = m.group(1);
            int n = Integer.parseInt(m.group(2));
            String tail = m.group(3);
            if (NON_REQUIREMENT_YEARS.matcher(pre + " " + tail).find()) continue;
            out.add(n);
        }
        return out.isEmpty() ? 0 : Collections.min(out);
    }

    private static final Pattern SENIOR_TITLE_RE = Pattern.compile(
            "\\b(?:senior|sr|staff|principal|lead|leads|director|mgr|manager|managing|"
                    + "architect|head|vp|svp|evp|distinguished|fellow|experienced|expert|"
                    + "ii|iii|iv|vi|vii|viii)\\b"
                    + "|\\b(?:l|lvl|level|grade|ic|t)\\s*-?\\s*[2-9]\\b"
                    + "|\\b(?:engineer|developer|sde|swe|programmer|analyst|scientist)\\s+[2-9]\\b"
                    + "(?!\\s*(?:month|months|week|weeks|year|years|yr|yrs|mo|day|days|hour|hours|"
                    + "opening|openings|position|positions|role|roles|spot|spots|seat|seats))");

    public static boolean isSeniorTitle(String title) {
        if (title == null) return false;
        return SENIOR_TITLE_RE.matcher(title.toLowerCase()).find();
    }

    private record SponsorBlockRule(String reason, Pattern pattern) {}

    private static final List<SponsorBlockRule> SPONSOR_BLOCK = List.of(
            new SponsorBlockRule("does not offer sponsorship", Pattern.compile(
                    "\\b(?:not able to|unable to|cannot|can ?not|will not|won'?t|"
                            + "do(?:es)? not|are not able to|not (?:currently )?(?:able|in a position) to)\\b"
                            + "[^.\\n]{0,18}\\bsponsor")),
            new SponsorBlockRule("no sponsorship", Pattern.compile(
                    "\\b(?:no|without)\\b[^.\\n]{0,18}\\b(?:visa )?sponsorship\\b")),
            new SponsorBlockRule("sponsorship not available", Pattern.compile(
                    "\\bsponsorship\\b[^.\\n]{0,20}\\b(?:not|isn'?t|won'?t be)\\b[^.\\n]{0,15}"
                            + "\\b(?:available|offered|provided|possible)\\b")),
            new SponsorBlockRule("not eligible for sponsorship", Pattern.compile(
                    "\\bnot\\b[^.\\n]{0,15}\\beligible\\b[^.\\n]{0,18}\\bsponsorship\\b")),
            new SponsorBlockRule("must not require sponsorship", Pattern.compile(
                    "\\bmust not require\\b[^.\\n]{0,15}\\bsponsorship\\b")),
            new SponsorBlockRule("US citizens only", Pattern.compile(
                    "\\bu\\.?\\s?s\\.?\\s*citizens?\\s*only\\b")),
            new SponsorBlockRule("must be a US citizen", Pattern.compile(
                    "\\bmust be (?:a |an )?(?:u\\.?\\s?s\\.?|united states) citizens?\\b")),
            new SponsorBlockRule("US citizenship required", Pattern.compile(
                    "\\b(?:u\\.?\\s?s\\.?|united states)\\s+citizenship\\b|"
                            + "\\b(?:require[sd]?|need(?:s|ed)?|must (?:hold|have|possess|be))\\b"
                            + "[^.\\n]{0,18}\\bcitizenship\\b")),
            new SponsorBlockRule("security clearance required", Pattern.compile(
                    "\\b(?:security|secret|top[- ]secret|ts/sci)\\s+clearance\\b|"
                            + "\\bclearance\\b[^.\\n]{0,18}\\b(?:required|needed|mandatory|active|eligible)\\b|"
                            + "\\brequires?\\b[^.\\n]{0,25}\\bclearance\\b|\\bts/sci\\b")));

    public static String detectSponsorshipBlock(String text) {
        String tl = normalize(text);
        if (tl.isEmpty() || (!tl.contains("sponsor") && !tl.contains("citizen") && !tl.contains("clearance"))) {
            return "";
        }
        for (SponsorBlockRule rule : SPONSOR_BLOCK) {
            if (rule.pattern().matcher(tl).find()) return rule.reason();
        }
        return "";
    }

    private static final Set<String> US_STATE_CODES = Set.of(
            "al", "ak", "az", "ar", "ca", "co", "ct", "de", "fl", "ga", "hi", "id", "il",
            "in", "ia", "ks", "ky", "la", "me", "md", "ma", "mi", "mn", "ms", "mo", "mt",
            "ne", "nv", "nh", "nj", "nm", "ny", "nc", "nd", "oh", "ok", "or", "pa", "ri",
            "sc", "sd", "tn", "tx", "ut", "vt", "va", "wa", "wv", "wi", "wy", "dc");

    private static final List<String> US_WORDS = List.of(
            "united states", "u.s.a", "usa", "u.s.", "remote, us", "remote (us",
            "remote - us", "remote-us", "us remote", "remote us", "us-remote",
            "anywhere in the us", "us based", "u.s.-based");

    private static final List<String> US_CITIES = List.of(
            "san francisco", "new york", "seattle", "austin", "boston", "atlanta",
            "chicago", "los angeles", "san jose", "mountain view", "palo alto",
            "sunnyvale", "menlo park", "bellevue", "redmond", "denver", "dallas",
            "houston", "san diego", "san mateo", "washington", "arlington",
            "nyc", "bay area", "silicon valley", "brooklyn", "pittsburgh", "raleigh",
            "durham", "charlotte", "phoenix", "salt lake city", "minneapolis",
            "detroit", "columbus", "nashville", "miami", "tampa", "kansas city",
            "santa clara", "santa monica", "culver city", "irvine", "plano",
            "reston", "mclean", "boulder", "ann arbor");

    private static final List<String> FOREIGN_LOC = List.of(
            "germany", "berlin", "munich", "münchen", "united kingdom", " uk", "u.k.", "london",
            "manchester", "edinburgh", "france", "paris", "netherlands", "amsterdam", "ireland",
            "dublin", "canada", "toronto", "vancouver", "montreal", "ottawa", "waterloo, on",
            "india", "bangalore", "bengaluru", "hyderabad", "pune", "mumbai", "new delhi",
            "gurgaon", "gurugram", "noida", "chennai", "kolkata", "kharagpur", "delhi",
            "ahmedabad", "coimbatore", "kochi", "thiruvananthapuram", "trivandrum", "jaipur",
            "indore", "bhubaneswar", "chandigarh", "mohali", "lucknow", "surat", "vadodara",
            "nagpur", "mysore", "mangalore", "visakhapatnam", "vizag", "singapore", "australia",
            "sydney", "melbourne", "japan", "tokyo", "israel", "tel aviv", "switzerland",
            "zurich", "zürich", "sweden", "stockholm", "denmark", "copenhagen", "norway", "oslo",
            "finland", "helsinki", "spain", "madrid", "barcelona", "portugal", "lisbon", "poland",
            "warsaw", "krakow", "kraków", "czech", "prague", "austria", "vienna", "belgium",
            "brussels", "italy", "milan", "brazil", "são paulo", "sao paulo", "mexico city",
            "colombia", "bogota", "bogotá", "argentina", "buenos aires", "china", "shanghai",
            "beijing", "shenzhen", "hong kong", "taiwan", "taipei", "philippines", "manila",
            "indonesia", "jakarta", "malaysia", "kuala lumpur", "thailand", "bangkok", "vietnam",
            "hanoi", "ho chi minh", "dubai", "abu dhabi", "riyadh", "cairo", "lagos", "nairobi",
            "johannesburg", "cape town", "new zealand", "auckland", "emea", "apac", "latam",
            "europe", "england", "scotland", "wales", "ontario", "quebec", "romania", "bucharest",
            "ukraine", "kyiv", "kiev", "turkey", "istanbul", "greece", "hungary", "budapest",
            "estonia", "tallinn", "lithuania", "latvia", "bulgaria", "serbia", "belgrade",
            "south korea", "seoul", "pakistan", "karachi", "lahore", "bangladesh", "dhaka",
            "sri lanka", "colombo", "nigeria", "kenya", "egypt", "morocco", "chile", "santiago",
            "peru", "lima", "uruguay", "costa rica", "panama", "guatemala");

    private static final Pattern STATE_CODE_RE = Pattern.compile(",\\s*([a-z]{2})\\b");

    public static String detectUsLocation(String text) {
        String t = text == null ? "" : text.toLowerCase();
        if (t.strip().isEmpty()) return "unknown";
        for (String w : US_WORDS) if (t.contains(w)) return "us";
        Matcher m = STATE_CODE_RE.matcher(t);
        while (m.find()) {
            if (US_STATE_CODES.contains(m.group(1))) return "us";
        }
        for (String c : US_CITIES) if (t.contains(c)) return "us";
        for (String f : FOREIGN_LOC) if (t.contains(f)) return "foreign";
        return "unknown";
    }

    private static final Pattern HYBRID_RE = Pattern.compile("hybrid");
    private static final Pattern REMOTE_RE = Pattern.compile("(?<!no )(?<!not )(?<!non-)\\bremote\\b");
    private static final Pattern ONSITE_RE = Pattern.compile("\\b(on-?site|in[- ]office|in[- ]person)\\b");

    public static String detectWorkMode(String text) {
        String tl = normalize(text);
        if (tl.isEmpty()) return "";
        if (HYBRID_RE.matcher(tl).find()) return "Hybrid";
        if (REMOTE_RE.matcher(tl).find() && !tl.contains("not remote")) return "Remote";
        if (ONSITE_RE.matcher(tl).find()) return "Onsite";
        return "";
    }

    private TextUtils() {}
}
