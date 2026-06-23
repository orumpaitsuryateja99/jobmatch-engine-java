package com.orumpati.jobmatch.resume;

import com.orumpati.jobmatch.model.*;
import com.orumpati.jobmatch.text.TextUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Java port of app/resume_parser.py — turn a resume file into a structured Profile.
 *
 * Supports .pdf (PDFBox), .docx (Apache POI), .txt/.md (plain text), and .tex
 * (routed through LatexResumeService.texToText; the raw source is also kept as
 * the active LaTeX template). Output flows through ATS scoring, tailoring, and
 * PDF generation, so the Profile shape mirrors the Python dict exactly. */
@Service
public class ResumeParser {

    private static final Pattern EMAIL_RE = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern PHONE_RE = Pattern.compile("\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}");
    private static final Pattern BULLET_RE = Pattern.compile("^[\\-•‣◦⁃∙*▪·●▸>]+\\s*");

    private final LatexResumeService latexResumeService;
    private final LatexMasterParser latexMasterParser;

    public ResumeParser(LatexResumeService latexResumeService, LatexMasterParser latexMasterParser) {
        this.latexResumeService = latexResumeService;
        this.latexMasterParser = latexMasterParser;
    }

    public String readResumeText(Path path) throws IOException {
        String ext = extOf(path);
        switch (ext) {
            case ".pdf": return readPdf(path);
            case ".docx": return readDocx(path);
            case ".tex": return latexResumeService.texToText(Files.readString(path, StandardCharsets.UTF_8));
            case ".txt": case ".md": return Files.readString(path, StandardCharsets.UTF_8);
            default: throw new IllegalArgumentException("Unsupported resume format: " + ext);
        }
    }

    private String extOf(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private String readPdf(Path path) throws IOException {
        try (PDDocument doc = loadPdf(path)) {
            return new ColumnAwarePdfStripper().getText(doc);
        }
    }

    private PDDocument loadPdf(Path path) throws IOException {
        return org.apache.pdfbox.Loader.loadPDF(path.toFile());
    }

    private List<String> readPdfLinks(Path path) throws IOException {
        List<String> links = new ArrayList<>();
        try (PDDocument doc = loadPdf(path)) {
            for (var page : doc.getPages()) {
                for (PDAnnotation annot : page.getAnnotations()) {
                    if (annot instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uri) {
                        if (uri.getURI() != null) links.add(uri.getURI());
                    }
                }
            }
        }
        return links;
    }

    private String readDocx(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path); XWPFDocument doc = new XWPFDocument(in)) {
            return doc.getParagraphs().stream().map(XWPFParagraph::getText).collect(Collectors.joining("\n"));
        }
    }

    public Profile parseResume(Path path) throws IOException {
        String raw = readResumeText(path);
        Profile profile = buildProfileFromText(raw, null);
        String ext = extOf(path);
        if (ext.equals(".pdf")) {
            applyLinks(profile, readPdfLinks(path));
        }
        if (ext.equals(".tex")) {
            String texSrc = Files.readString(path, StandardCharsets.UTF_8);
            profile.setLatexTemplate(texSrc);
            // The generic tex→text path mangles custom-macro résumés (drops \resumeItem
            // bullets, merges roles). When the source uses those macros, re-derive the
            // structured sections straight from the macros for correct, complete output.
            latexMasterParser.enrich(profile, texSrc);
        }
        return profile;
    }

    public Profile buildProfileFromText(String raw, List<String> targetRoles) {
        Matcher em = EMAIL_RE.matcher(raw);
        String email = em.find() ? em.group() : null;
        Matcher ph = PHONE_RE.matcher(raw);
        String phone = ph.find() ? ph.group() : null;

        String name = null;
        for (String line : raw.split("\n", -1)) {
            String s = line.strip();
            if (!s.isEmpty() && !EMAIL_RE.matcher(s).find() && !PHONE_RE.matcher(s).find() && s.length() < 60) {
                name = s;
                break;
            }
        }

        Map<String, String> links = extractLinks(raw);
        List<SkillCategory> skillCategories = parseSkillCategories(raw);
        Set<String> detectedSkills = new TreeSet<>(TextUtils.detectSkills(raw));
        detectedSkills.addAll(skillsFromSkillCategories(skillCategories));
        Set<String> detectedTools = new TreeSet<>(TextUtils.detectTools(raw));
        detectedTools.addAll(toolsFromSkillCategories(skillCategories));

        Profile profile = new Profile();
        profile.setName(name != null ? name : "Candidate");
        profile.setEmail(email);
        profile.setPhone(phone);
        profile.setLinks(links);
        String summary = extractSummary(raw);
        profile.setSummary(!summary.isBlank() ? summary : guessSummary(raw));
        profile.setSkillCategories(skillCategories);
        profile.setSkillPhrases(flattenSkillCategories(skillCategories));
        profile.setSkills(new ArrayList<>(detectedSkills));
        profile.setTools(new ArrayList<>(detectedTools));
        profile.setDomains(new ArrayList<>(new TreeSet<>(TextUtils.detectDomains(raw))));
        List<Project> projects = parseProjects(raw);
        profile.setProjects(!projects.isEmpty() ? projects : guessProjectSections(raw));
        List<Experience> experience = parseExperience(raw);
        profile.setExperience(!experience.isEmpty() ? experience : guessExperienceSections(raw));
        profile.setEducation(parseEducation(raw));
        profile.setAdditional(parseAdditional(raw));
        profile.setTargetRoles(targetRoles != null ? targetRoles
                : List.of("software engineer", "backend engineer", "full stack engineer"));
        profile.setExperienceYears(0);
        profile.setRawText(raw);
        profile.setLatexTemplate("");
        return profile;
    }

    private String cleanLine(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }

    private List<String> lines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String x : raw.split("\n", -1)) {
            String c = cleanLine(x);
            if (!c.isEmpty()) out.add(c);
        }
        return out;
    }

    private String normHeading(String s) {
        String c = cleanLine(s).toLowerCase();
        return c.endsWith(":") ? c.substring(0, c.length() - 1) : c;
    }

    private static final List<String> SECTION_HEADERS = List.of(
            "experience", "work experience", "employment", "professional experience",
            "projects", "technical projects", "academic projects", "personal projects",
            "education", "skills", "technical skills", "certifications", "certification",
            "awards", "achievements", "honors", "summary", "objective", "profile",
            "publications", "leadership", "activities", "interests", "references", "coursework");

    private List<String> blockBetween(String raw, String start, String end) {
        List<String> lns = lines(raw);
        List<String> headings = lns.stream().map(this::normHeading).toList();
        int i = headings.indexOf(start.toLowerCase());
        if (i < 0) return List.of();
        i += 1;
        int j = lns.size();
        if (end != null) {
            int idx = headings.subList(i, headings.size()).indexOf(end.toLowerCase());
            if (idx >= 0) j = i + idx;
        } else {
            Set<String> known = new HashSet<>(SECTION_HEADERS);
            for (int k = i; k < lns.size(); k++) {
                if (known.contains(headings.get(k))) { j = k; break; }
            }
        }
        return lns.subList(i, j);
    }

    private List<String> blockBetweenAny(String raw, List<String> starts, String end) {
        for (String s : starts) {
            List<String> block = blockBetween(raw, s, end);
            if (!block.isEmpty()) return block;
        }
        return List.of();
    }

    private static final Pattern DATE_RANGE_RE = Pattern.compile(
            "\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\w*\\s+\\d{4}\\s*[-–]\\s*(?:present|\\w+\\s+\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_MARKER_RE = Pattern.compile("(?:\\[[^\\]]+\\]\\s*)+");

    private static final Pattern TRAILING_LINK_MARKERS_RE = Pattern.compile("\\s*(?:\\[[^\\]]+\\]\\s*)+$");

    /** PDFBox's column-aware stripper doesn't always break a new line at a
     * hyperlink-annotation boundary the way PyMuPDF does, so an inline "[Live]
     * [GitHub]" marker can stay glued to the title text it follows. Strip it from
     * the title itself; isLinkMarker() still recognizes the run as its own line
     * when the stripper DOES separate it. */
    private String stripTrailingLinkMarkers(String s) {
        return TRAILING_LINK_MARKERS_RE.matcher(s).replaceFirst("").strip();
    }

    private boolean isDateRange(String s) { return DATE_RANGE_RE.matcher(s).find(); }
    private boolean isLinkMarker(String s) { return s != null && LINK_MARKER_RE.matcher(s).matches(); }
    private boolean isBullet(String s) { return s != null && BULLET_RE.matcher(s).find(); }

    private Map.Entry<List<String>, Integer> collectBullets(List<String> lns, int start) {
        List<String> bullets = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int i = start;
        while (i < lns.size()) {
            String s = lns.get(i);
            if (isBullet(s)) {
                if (cur.length() > 0) bullets.add(cur.toString());
                cur = new StringBuilder(BULLET_RE.matcher(s).replaceFirst("").strip());
            } else if (cur.length() > 0) {
                cur.append(" ").append(s);
            } else {
                break;
            }
            i++;
        }
        if (cur.length() > 0) bullets.add(cur.toString());
        return Map.entry(bullets, i);
    }

    private Map<String, String> extractLinks(String raw) {
        Map<String, String> links = new HashMap<>();
        Matcher li = Pattern.compile("(?:https?://)?linkedin\\.com/[^\\s·|]+", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (li.find()) links.put("linkedin", li.group().replace("https://", ""));
        Matcher gh = Pattern.compile("(?:https?://)?github\\.com/[^\\s·|]+", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (gh.find()) links.put("github", gh.group().replace("https://", ""));
        Matcher pf = Pattern.compile("(?:https?://)?[\\w.-]+\\.github\\.io/[^\\s·|]+", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (pf.find()) links.put("portfolio", pf.group().replace("https://", ""));
        return links;
    }

    private List<String> splitCsvHonoringParens(String text) {
        List<String> items = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int depth = 0;
        if (text == null) return items;
        for (char ch : text.toCharArray()) {
            if (ch == '(') depth++;
            else if (ch == ')' && depth > 0) depth--;
            if (ch == ',' && depth == 0) {
                String item = cleanLine(buf.toString());
                if (!item.isEmpty()) items.add(item);
                buf = new StringBuilder();
            } else {
                buf.append(ch);
            }
        }
        String item = cleanLine(buf.toString());
        if (!item.isEmpty()) items.add(item);
        return items;
    }

    private List<SkillCategory> parseSkillCategories(String raw) {
        List<String> lns = blockBetween(raw, "technical skills", "work experience");
        List<SkillCategory> categories = new ArrayList<>();
        for (String line : lns) {
            if (line.contains(":")) {
                int idx = line.indexOf(':');
                String label = line.substring(0, idx);
                String rest = line.substring(idx + 1);
                categories.add(new SkillCategory(cleanLine(label), splitCsvHonoringParens(rest)));
            } else if (!categories.isEmpty()) {
                categories.get(categories.size() - 1).getItems().addAll(splitCsvHonoringParens(line));
            }
        }
        return categories.stream()
                .filter(c -> c.getCategory() != null && !c.getCategory().isBlank() && !c.getItems().isEmpty())
                .collect(Collectors.toList());
    }

    private List<String> flattenSkillCategories(List<SkillCategory> categories) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (SkillCategory cat : categories) {
            for (String item : cat.getItems()) {
                String key = item.toLowerCase();
                if (seen.add(key)) out.add(item);
            }
        }
        return out;
    }

    private static final Map<String, String> EXACT_SKILL_MAP = Map.ofEntries(
            Map.entry("c", "c"), Map.entry("c++", "c++"), Map.entry("chart.js", "chart.js"),
            Map.entry("google gemini api", "google gemini api"), Map.entry("sam", "sam"),
            Map.entry("google calendar api", "google calendar api"), Map.entry("oauth", "oauth"),
            Map.entry("restful web services", "rest apis"), Map.entry("data structures & algorithms", "data structures"),
            Map.entry("object-oriented programming", "oop"), Map.entry("agile / scrum", "agile"),
            Map.entry("google cloud apis", "gcp"));

    private static final Map<String, String> EXACT_TOOL_MAP = Map.of(
            "git", "git", "github", "github", "vs code", "vs code", "postman", "postman");

    private Set<String> skillsFromSkillCategories(List<SkillCategory> categories) {
        Set<String> out = new HashSet<>();
        for (String item : flattenSkillCategories(categories)) {
            String key = item.replaceAll("\\s*\\([^)]*\\)", "").strip().toLowerCase();
            if (EXACT_SKILL_MAP.containsKey(key)) out.add(EXACT_SKILL_MAP.get(key));
        }
        return out;
    }

    private Set<String> toolsFromSkillCategories(List<SkillCategory> categories) {
        Set<String> out = new HashSet<>();
        for (String item : flattenSkillCategories(categories)) {
            String key = item.replaceAll("\\s*\\([^)]*\\)", "").strip().toLowerCase();
            if (EXACT_TOOL_MAP.containsKey(key)) out.add(EXACT_TOOL_MAP.get(key));
        }
        return out;
    }

    private void applyLinks(Profile profile, List<String> uris) {
        List<String> clean = uris.stream().filter(u -> u != null && !u.startsWith("mailto:")).toList();
        Map<String, String> links = profile.getLinks();
        for (String u : clean) {
            String lu = u.toLowerCase();
            if (lu.contains("linkedin.com") && !links.containsKey("linkedin")) {
                links.put("linkedin", u.replace("https://", ""));
            } else if (lu.contains("github.com") && !links.containsKey("github")
                    && lu.replaceAll("/+$", "").chars().filter(c -> c == '/').count() <= 3) {
                links.put("github", u.replace("https://", ""));
            } else if (lu.contains("github.io") && !links.containsKey("portfolio")) {
                links.put("portfolio", u);
            }
        }
        // Associate the résumé's hyperlink annotations with each project / experience by
        // matching URL path words against the item's name/company tokens (and vice-versa),
        // so EVERY item gets its Live + GitHub links — not just a hardcoded few.
        for (Project p : profile.getProjects()) {
            assignItemLinks(cleanLine(p.getName()) + " " + cleanLine(p.getTech()), p.getLinks(), clean);
        }
        for (Experience e : profile.getExperience()) {
            assignItemLinks(cleanLine(e.getCompany()) + " " + cleanLine(e.getRole()), e.getLinks(), clean);
        }
    }

    private static final Set<String> LINK_STOPWORDS = Set.of(
            "project", "system", "university", "college", "research", "assistant", "intern",
            "engineer", "engineering", "developer", "software", "technology", "institute",
            "data", "platform", "application", "applications", "team", "with", "and", "the");

    private Set<String> linkTokens(String text) {
        Set<String> out = new HashSet<>();
        for (String tok : (text == null ? "" : text).toLowerCase().split("[^a-z0-9]+")) {
            if (tok.length() >= 4 && !LINK_STOPWORDS.contains(tok)) out.add(tok);
        }
        return out;
    }

    /** Attach Live/GitHub URLs to an item when its name and a URL share a word. */
    private void assignItemLinks(String itemText, Map<String, String> target, List<String> urls) {
        String itemNorm = (itemText == null ? "" : itemText).toLowerCase().replaceAll("[^a-z0-9]", "");
        Set<String> nameTokens = linkTokens(itemText);
        boolean isViva = itemNorm.contains("viva") || itemNorm.contains("fit");
        for (String u : urls) {
            String lu = u.toLowerCase();
            if (lu.contains("linkedin.com")) continue;
            String urlNorm = lu.replaceAll("^https?://", "").replaceAll("[^a-z0-9]", "");
            Set<String> urlTokens = linkTokens(lu.replaceAll("[^a-z0-9]+", " "));
            boolean match = nameTokens.stream().anyMatch(urlNorm::contains)
                    || urlTokens.stream().anyMatch(t -> t.length() >= 5 && itemNorm.contains(t))
                    || (isViva && lu.contains("fitlife"));   // product name differs from company
            if (!match) continue;
            if (lu.contains("github.com")) target.putIfAbsent("github", u);
            else target.putIfAbsent("live", u);
        }
    }

    private String extractSummary(String raw) {
        List<String> lns = blockBetween(raw, "professional summary", "technical skills");
        if (lns.isEmpty()) {
            lns = blockBetweenAny(raw, List.of("summary", "professional summary", "objective", "profile", "about"), null);
        }
        return String.join(" ", lns).strip();
    }

    /** A résumé entry: the header lines (title + meta such as company/date/location)
     *  and the bullet lines beneath it. */
    private record Entry(List<String> header, List<String> bullets) {}

    /** Split a section's lines into multiple entries. An entry is a run of non-bullet
     *  header lines followed by its bullets. A new entry begins when, mid-bullets, a
     *  non-bullet line appears AND the current bullet already ended with sentence
     *  punctuation (so wrapped continuation lines stay attached to their bullet). */
    private List<Entry> parseEntries(List<String> lns) {
        List<Entry> entries = new ArrayList<>();
        int i = 0, n = lns.size();
        while (i < n) {
            List<String> header = new ArrayList<>();
            while (i < n && !isBullet(lns.get(i))) header.add(lns.get(i++));
            List<String> bullets = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            while (i < n) {
                String s = lns.get(i);
                if (isBullet(s)) {
                    if (cur.length() > 0) bullets.add(cur.toString());
                    cur = new StringBuilder(BULLET_RE.matcher(s).replaceFirst("").strip());
                    i++;
                } else if (cur.length() > 0 && !endsSentence(cur.toString())) {
                    cur.append(" ").append(s); i++;            // wrapped continuation
                } else {
                    break;                                      // new entry header starts here
                }
            }
            if (cur.length() > 0) bullets.add(cur.toString());
            if (!header.isEmpty() || !bullets.isEmpty()) entries.add(new Entry(header, bullets));
        }
        return entries;
    }

    private boolean endsSentence(String s) {
        if (s == null || s.isBlank()) return false;
        char c = s.strip().charAt(s.strip().length() - 1);
        return c == '.' || c == '!' || c == '?' || c == ';' || c == ')';
    }

    private static final Pattern BARE_YEAR_RE = Pattern.compile("^\\(?\\s*(?:19|20)\\d{2}\\s*\\)?$");

    private boolean isDateLike(String s) {
        if (s == null) return false;
        String t = s.strip();
        return isDateRange(t) || BARE_YEAR_RE.matcher(t).matches()
                || t.toLowerCase().matches(".*\\b(?:19|20)\\d{2}\\s*[-–—]\\s*(?:present|(?:19|20)\\d{2}).*");
    }

    private boolean isLocationLike(String s) {
        if (s == null) return false;
        String t = s.strip();
        if (t.length() > 45 || isDateLike(t)) return false;
        return t.contains(",") || t.toLowerCase().matches(".*\\b(remote|hybrid|onsite|on-site)\\b.*");
    }

    private List<Experience> parseExperience(String raw) {
        List<String> lns = blockBetween(raw, "work experience", "projects");
        if (lns.isEmpty()) lns = blockBetween(raw, "experience", "projects");
        List<Experience> out = new ArrayList<>();
        for (Entry en : parseEntries(lns)) {
            if (en.header().isEmpty()) continue;
            String role = stripTrailingLinkMarkers(en.header().get(0));
            String date = "", location = "";
            List<String> rest = new ArrayList<>(en.header().subList(1, en.header().size()));
            rest.removeIf(this::isLinkMarker);
            for (String m : rest) if (date.isEmpty() && isDateLike(m)) date = m;
            for (String m : rest) if (location.isEmpty() && !m.equals(date) && isLocationLike(m)) location = m;
            StringBuilder company = new StringBuilder();
            for (String m : rest) {
                if (m.equals(date) || m.equals(location)) continue;
                if (company.length() > 0) company.append(" ");
                company.append(m);
            }
            if (role.isBlank() && company.length() == 0 && en.bullets().isEmpty()) continue;
            Experience e = new Experience();
            e.setRole(role);
            e.setCompany(company.toString());
            e.setDate(date);
            e.setLocation(location);
            e.setBullets(en.bullets());
            out.add(e);
        }
        return out;
    }

    private List<Project> parseProjects(String raw) {
        List<String> lns = blockBetween(raw, "projects", "education");
        if (lns.isEmpty()) lns = blockBetweenAny(raw, List.of("projects", "technical projects",
                "academic projects", "personal projects"), "education");
        List<Project> out = new ArrayList<>();
        for (Entry en : parseEntries(lns)) {
            if (en.header().isEmpty() || en.bullets().isEmpty()) continue;
            String title = stripTrailingLinkMarkers(en.header().get(0));
            List<String> rest = new ArrayList<>(en.header().subList(1, en.header().size()));
            rest.removeIf(this::isLinkMarker);
            String date = "", tech = "";
            for (String m : rest) if (date.isEmpty() && isDateLike(m)) date = m;
            for (String m : rest) if (tech.isEmpty() && !m.equals(date)) tech = m;
            String name = title.contains("|") ? title.substring(0, title.indexOf('|')).strip() : title;
            String subtitle = title.contains("|") ? title.substring(title.indexOf('|') + 1).strip() : "";
            if (name.isEmpty()) continue;
            Project p = new Project();
            p.setName(name);
            p.setSubtitle(subtitle);
            p.setDate(date);
            p.setTech(tech);
            p.setBullets(en.bullets());
            out.add(p);
        }
        return out;
    }

    private List<Education> parseEducation(String raw) {
        List<String> lns = blockBetween(raw, "education", "additional");
        if (lns.isEmpty()) {
            lns = blockBetweenAny(raw, List.of("education", "academic background", "academics",
                    "educational qualifications"), null);
        }
        List<Education> out = new ArrayList<>();
        int i = 0;
        while (i + 3 < lns.size()) {
            Education e = new Education();
            e.setSchool(lns.get(i));
            e.setLocation(lns.get(i + 1));
            String degreeLine = lns.get(i + 2);
            e.setDegree(degreeLine.contains("|") ? degreeLine.substring(0, degreeLine.indexOf('|')).strip() : degreeLine);
            e.setDetail(degreeLine.contains("|") ? degreeLine.substring(degreeLine.indexOf('|') + 1).strip() : "");
            e.setDates(lns.get(i + 3));
            out.add(e);
            i += 4;
        }
        return out;
    }

    private List<String> parseAdditional(String raw) {
        List<String> lns = blockBetween(raw, "additional", null);
        return collectBullets(lns, 0).getKey();
    }

    private String guessSummary(String raw) {
        for (String line : raw.split("\n", -1)) {
            String s = line.strip();
            String low = s.toLowerCase();
            if (s.length() > 60 && !low.startsWith("skills") && !low.startsWith("experience") && !low.startsWith("education")) {
                return s;
            }
        }
        return "";
    }

    private static final Pattern HEADER_HINT = Pattern.compile(
            "\\b(20\\d{2}|present|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|"
                    + "engineer|developer|intern|analyst|research|manager|assistant|lead|scientist)\\b",
            Pattern.CASE_INSENSITIVE);

    private String sectionBlock(String raw, Set<String> names) {
        String[] lns = raw.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lns.length; i++) {
            String s = lns[i].strip().toLowerCase();
            s = s.endsWith(":") ? s.substring(0, s.length() - 1) : s;
            if (!s.isEmpty() && (names.contains(s)
                    || (names.stream().anyMatch(s::startsWith) && lns[i].strip().length() < 40))) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) return "";
        int end = lns.length;
        for (int j = start; j < lns.length; j++) {
            String s = lns[j].strip().toLowerCase();
            s = s.endsWith(":") ? s.substring(0, s.length() - 1) : s;
            if (!s.isEmpty() && SECTION_HEADERS.contains(s) && lns[j].strip().length() < 40) {
                end = j;
                break;
            }
        }
        return String.join("\n", Arrays.asList(lns).subList(start, end));
    }

    private record NamedBullets(String name, List<String> bullets) {}

    private List<NamedBullets> groupedSections(String block) {
        List<NamedBullets> result = new ArrayList<>();
        List<String> bulletsAcc = new ArrayList<>();
        String currentName = null;
        boolean haveGroup = false;
        String pend = "";
        for (String rawLine : block.split("\n", -1)) {
            String s = rawLine.strip();
            if (s.isEmpty()) {
                if (!pend.isEmpty()) {
                    String t = normalizeBullet(pend);
                    if (t.length() >= 20) {
                        if (!haveGroup) { currentName = "Highlights"; haveGroup = true; }
                        bulletsAcc.add(t);
                    }
                    pend = "";
                }
                continue;
            }
            boolean bullet = BULLET_RE.matcher(s).find();
            boolean looksHeader = !bullet && s.length() < 70 && HEADER_HINT.matcher(s).find()
                    && !(s.endsWith(".") || s.endsWith(","));
            if (bullet) {
                if (!pend.isEmpty()) {
                    String t = normalizeBullet(pend);
                    if (t.length() >= 20) {
                        if (!haveGroup) { currentName = "Highlights"; haveGroup = true; }
                        bulletsAcc.add(t);
                    }
                }
                pend = BULLET_RE.matcher(s).replaceFirst("");
            } else if (looksHeader) {
                if (!pend.isEmpty()) {
                    String t = normalizeBullet(pend);
                    if (t.length() >= 20) {
                        if (!haveGroup) { currentName = "Highlights"; haveGroup = true; }
                        bulletsAcc.add(t);
                    }
                    pend = "";
                }
                if (haveGroup && !bulletsAcc.isEmpty()) {
                    result.add(new NamedBullets(currentName, new ArrayList<>(bulletsAcc)));
                    bulletsAcc.clear();
                }
                currentName = s.replaceAll("\\s+", " ");
                currentName = currentName.length() > 80 ? currentName.substring(0, 80) : currentName;
                haveGroup = true;
            } else if (!pend.isEmpty()) {
                pend += " " + s;
            } else {
                pend = s;
            }
        }
        if (!pend.isEmpty()) {
            String t = normalizeBullet(pend);
            if (t.length() >= 20) {
                if (!haveGroup) { currentName = "Highlights"; haveGroup = true; }
                bulletsAcc.add(t);
            }
        }
        if (haveGroup && !bulletsAcc.isEmpty()) {
            result.add(new NamedBullets(currentName, bulletsAcc));
        }
        return result.size() > 6 ? result.subList(0, 6) : result;
    }

    private String normalizeBullet(String text) {
        return text.strip().replaceAll("^[\\s\\-•*▪·\\t]+|[\\s\\-•*▪·\\t]+$", "").replaceAll("\\s+", " ").strip();
    }

    private List<Project> guessProjectSections(String raw) {
        String block = sectionBlock(raw, Set.of("project", "projects"));
        if (block.isBlank()) return List.of();
        List<Project> out = new ArrayList<>();
        for (NamedBullets g : groupedSections(block)) {
            if (g.bullets().isEmpty()) continue;
            Project p = new Project();
            p.setName(g.name());
            p.setBullets(g.bullets());
            out.add(p);
        }
        return out;
    }

    private List<Experience> guessExperienceSections(String raw) {
        String block = sectionBlock(raw, Set.of("experience", "employment", "work"));
        if (block.isBlank()) return List.of();
        List<Experience> out = new ArrayList<>();
        for (NamedBullets g : groupedSections(block)) {
            if (g.bullets().isEmpty()) continue;
            Experience e = new Experience();
            e.setCompany(g.name());
            e.setBullets(g.bullets());
            out.add(e);
        }
        return out;
    }
}
