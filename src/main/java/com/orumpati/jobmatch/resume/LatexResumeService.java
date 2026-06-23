package com.orumpati.jobmatch.resume;

import com.orumpati.jobmatch.model.Education;
import com.orumpati.jobmatch.model.Experience;
import com.orumpati.jobmatch.model.Profile;
import com.orumpati.jobmatch.model.Project;
import com.orumpati.jobmatch.model.SkillCategory;
import com.orumpati.jobmatch.text.TextUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Java port of the keyword/ATS/LaTeX-template parts of app/latex_resume.py.
 * The strict CO-STAR AI-tailoring prompt builder is intentionally NOT ported here —
 * that step is a human pasting into an LLM, not part of the core pipeline. */
@Service
public class LatexResumeService {

    private static final Set<String> ATS_STOPWORDS = Set.of(
            "about", "across", "after", "again", "also", "and", "apply", "are", "based",
            "build", "business", "candidate", "company", "customer", "data", "design",
            "develop", "engineer", "engineering", "experience", "help", "including",
            "looking", "opportunity", "product", "products", "requirements", "role",
            "software", "strong", "support", "team", "teams", "technology", "using",
            "with", "work", "working", "years", "you", "your");

    private static final Map<Character, String> ESCAPES = Map.ofEntries(
            Map.entry('\\', "\\textbackslash{}"), Map.entry('&', "\\&"), Map.entry('%', "\\%"),
            Map.entry('$', "\\$"), Map.entry('#', "\\#"), Map.entry('_', "\\_"),
            Map.entry('{', "\\{"), Map.entry('}', "\\}"), Map.entry('~', "\\textasciitilde{}"),
            Map.entry('^', "\\textasciicircum{}"));

    public String latexEscape(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        for (char ch : text.toCharArray()) {
            out.append(ESCAPES.getOrDefault(ch, String.valueOf(ch)));
        }
        return out.toString();
    }

    public String stripLatexFences(String code) {
        String t = code == null ? "" : code.strip();
        t = t.replaceFirst("(?i)^```(?:latex|tex)?\\s*", "");
        t = t.replaceFirst("\\s*```$", "").strip();
        return t;
    }

    public String texToText(String raw) {
        String t = raw == null ? "" : raw;
        t = t.replaceAll("(?m)^\\s*%[^\\n]*", "");
        t = Pattern.compile("\\\\(section|subsection|subsubsection)\\*?\\{([^}]*)\\}", Pattern.CASE_INSENSITIVE)
                .matcher(t).replaceAll("\n\n$2\n");
        t = Pattern.compile("\\\\textbf\\{([^}]*)\\}", Pattern.CASE_INSENSITIVE).matcher(t).replaceAll("$1");
        t = Pattern.compile("\\\\(textit|emph|underline|texttt|textsc)\\{([^}]*)\\}", Pattern.CASE_INSENSITIVE)
                .matcher(t).replaceAll("$2");
        t = Pattern.compile("\\\\href\\{[^}]*\\}\\{([^}]*)\\}", Pattern.CASE_INSENSITIVE).matcher(t).replaceAll("$1");
        t = Pattern.compile("\\\\item\\b", Pattern.CASE_INSENSITIVE).matcher(t).replaceAll("\n- ");
        t = t.replace("\\\\", "\n");
        t = Pattern.compile("\\\\(begin|end)\\{[^}]*\\}", Pattern.CASE_INSENSITIVE).matcher(t).replaceAll("\n");
        t = Pattern.compile("\\\\[a-zA-Z@]+(\\[[^\\]]*\\])?(\\{[^}]*\\})?").matcher(t).replaceAll(" ");
        t = Pattern.compile("\\\\([%#_~^])").matcher(t).replaceAll("$1");
        t = t.replaceAll("[{}$&]", " ");
        t = t.replaceAll("[ \\t]{2,}", " ");
        t = t.replaceAll("\\n{3,}", "\n\n");
        return t.strip();
    }

    private static final Pattern JD_TOKEN_RE = Pattern.compile("[A-Za-z][A-Za-z0-9+#./-]{2,}");
    private static final Pattern KW_TOKEN_RE = Pattern.compile("[a-z0-9+#.]+");
    private static final Set<Character> TECH_CHARS = Set.of('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '#', '/');

    public List<String> extractJdKeywords(String jdText) {
        String jd = jdText == null ? "" : jdText;
        LinkedHashMap<String, String> display = new LinkedHashMap<>();
        Set<String> found = new LinkedHashSet<>();
        found.addAll(TextUtils.detectSkills(jd));
        found.addAll(TextUtils.detectTools(jd));
        for (String x : found) display.put(x.toLowerCase(), x);

        Matcher m = JD_TOKEN_RE.matcher(jd);
        while (m.find()) {
            String clean = m.group().replaceAll("^[./-]+|[./-]+$", "");
            String low = clean.toLowerCase();
            if (clean.length() < 3 || clean.length() > 24 || ATS_STOPWORDS.contains(low)) continue;
            boolean looksTech = clean.chars().anyMatch(c -> Character.isUpperCase((char) c))
                    || clean.chars().anyMatch(c -> TECH_CHARS.contains((char) c));
            if (looksTech && !display.containsKey(low)) {
                display.put(low, clean);
            }
        }
        return display.values().stream().limit(40).collect(Collectors.toList());
    }

    public Set<String> resumeTokenSet(String resumeText) {
        Set<String> out = new HashSet<>();
        Matcher m = KW_TOKEN_RE.matcher(resumeText == null ? "" : resumeText.toLowerCase());
        while (m.find()) out.add(m.group());
        return out;
    }

    public AtsMatchResult computeTextAtsMatch(String resumeText, String jdText, Set<String> resumeTokens) {
        if (jdText == null || jdText.strip().length() < 120) return null;
        String rt = resumeText == null ? "" : resumeText.toLowerCase();
        List<String> keywords = extractJdKeywords(jdText);
        if (keywords.isEmpty()) return null;
        Set<String> rtTokens = resumeTokens != null ? resumeTokens : resumeTokenSet(rt);
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String kw : keywords) {
            String low = kw.toLowerCase();
            if (rtTokens.contains(low)) {
                matched.add(kw);
            } else if (KW_TOKEN_RE.matcher(low).matches()) {
                missing.add(kw);
            } else {
                Pattern pat = Pattern.compile("(^|[^a-z0-9+#.])" + Pattern.quote(low) + "([^a-z0-9+#.]|$)");
                if (pat.matcher(rt).find()) matched.add(kw); else missing.add(kw);
            }
        }
        return new AtsMatchResult(Math.round(100f * matched.size() / keywords.size()), matched, missing, keywords.size());
    }

    public AtsMatchResult computeLatexAtsMatch(String latex, String jdText) {
        return computeTextAtsMatch(texToText(latex), jdText, null);
    }

    private String pretty(String skill) {
        Set<String> upper = Set.of("aws", "gcp", "sql", "oop", "nlp", "llm", "json", "rest apis", "html5", "css3");
        return upper.contains(skill) ? skill.toUpperCase() : titleCase(skill);
    }

    private String titleCase(String s) {
        String[] parts = s.split(" ");
        return Arrays.stream(parts)
                .map(p -> p.isEmpty() ? p : Character.toUpperCase(p.charAt(0)) + p.substring(1))
                .collect(Collectors.joining(" "));
    }

    /** Built-in one-page LaTeX template, ported from build_latex_template() in
     * app/latex_resume.py. Built ONLY from the candidate's own parsed profile —
     * never hard-codes content — so it can't inject someone else's data. */
    /** Highest squeeze level — the tightest the one-page fit loop will go. */
    public static final int MAX_SQUEEZE = 4;

    /** Pick a starting squeeze level from how much content the résumé has. The PDF
     *  endpoint escalates from here until the result is a single page. */
    public int autoSqueezeLevel(Profile profile) {
        int expN = profile.getExperience() == null ? 0 : profile.getExperience().size();
        int projN = profile.getProjects() == null ? 0 : profile.getProjects().size();
        int units = expN * 3 + projN * 2
                + (profile.getEducation() == null ? 0 : profile.getEducation().size())
                + (profile.getAdditional() == null ? 0 : profile.getAdditional().size());
        if (units < 8) return 0;
        if (units < 12) return 1;
        if (units < 16) return 2;
        return 3;
    }

    public String buildLatexTemplate(Profile profile) {
        return buildLatexTemplate(profile, autoSqueezeLevel(profile));
    }

    /** Build the one-page LaTeX résumé at a given squeeze level (0 = roomy … 4 = cram).
     *  Higher levels shrink font, margins, and all vertical spacing so a long résumé
     *  still fits on a single page. */
    public String buildLatexTemplate(Profile profile, int level) {
        int lv = Math.max(0, Math.min(MAX_SQUEEZE, level));
        Map<String, String> links = profile.getLinks() == null ? Map.of() : profile.getLinks();

        // ---- squeeze-level spacing table: each level tighter than the last ----
        String fontPt   = new String[]{"11pt", "11pt", "10pt", "10pt", "10pt"}[lv];
        String itemSep  = new String[]{"4pt", "3pt", "2pt", "1pt", "1pt"}[lv];
        String topSep   = new String[]{"3pt", "2pt", "1pt", "1pt", "0pt"}[lv];
        String secBefore = new String[]{"13pt", "10pt", "6pt", "5pt", "4pt"}[lv];
        String secAfter = new String[]{"4pt", "3pt", "2pt", "2pt", "1pt"}[lv];
        String margin   = new String[]{"0.6in", "0.55in", "0.45in", "0.4in", "0.38in"}[lv];
        String ruleGap  = new String[]{"2pt", "2pt", "1pt", "1pt", "1pt"}[lv];
        int entryGapPt  = new int[]{12, 8, 4, 2, 1}[lv];
        String entryGap = "\\vspace{" + entryGapPt + "pt}\n";

        // ---- header: phone / email / linkedin / github / portfolio (all hyperlinked) ----
        List<String> contactParts = new ArrayList<>();
        if (notBlank(profile.getPhone())) contactParts.add(latexEscape(profile.getPhone()));
        if (notBlank(profile.getEmail())) contactParts.add(latexEscape(profile.getEmail()));
        contactParts.add(href(links.get("linkedin")));
        contactParts.add(href(links.get("github")));
        contactParts.add(labeledHref("Portfolio", links.get("portfolio")));
        String contactLine = contactParts.stream().filter(Objects::nonNull)
                .collect(Collectors.joining(" $\\cdot$ "));

        // ---- skills: keep the candidate's OWN categories, in their given order; flat fallback ----
        String skillsSection;
        if (profile.getSkillCategories() != null && !profile.getSkillCategories().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (SkillCategory c : profile.getSkillCategories()) {
                if (c.getItems() == null || c.getItems().isEmpty()) continue;
                String items = c.getItems().stream().map(this::latexEscape).collect(Collectors.joining(", "));
                sb.append("\\textbf{").append(latexEscape(c.getCategory())).append(":} ")
                        .append(items).append("\\\\\n");
            }
            skillsSection = sb.toString();
        } else {
            skillsSection = profile.getSkills().stream().limit(30)
                    .map(s -> latexEscape(pretty(s))).collect(Collectors.joining(", "));
        }

        // ---- experience: role [Live][GitHub] \hfill location / company \hfill date / Skills / bullets ----
        StringBuilder expBlocks = new StringBuilder();
        for (Experience e : profile.getExperience()) {
            String head = notBlank(e.getRole()) ? e.getRole() : (notBlank(e.getCompany()) ? e.getCompany() : "Experience");
            List<String> hdr = new ArrayList<>();
            hdr.add("\\textbf{" + latexEscape(head) + "}" + itemLinks(e.getLinks())
                    + (notBlank(e.getLocation()) ? " \\hfill " + latexEscape(e.getLocation()) : ""));
            if (notBlank(e.getCompany()) || notBlank(e.getDate())) {
                hdr.add("\\textit{" + latexEscape(e.getCompany()) + "}"
                        + (notBlank(e.getDate()) ? " \\hfill \\textit{" + latexEscape(e.getDate()) + "}" : ""));
            }
            if (notBlank(skillsLine(e.getSkills()))) hdr.add(skillsLine(e.getSkills()));
            expBlocks.append(entry(hdr, bullets(e.getBullets()))).append(entryGap);
        }

        // ---- projects: name [Live][GitHub] \hfill date / tech / Skills / bullets ----
        StringBuilder projBlocks = new StringBuilder();
        for (Project p : profile.getProjects()) {
            List<String> hdr = new ArrayList<>();
            hdr.add("\\textbf{" + latexEscape(p.getName()) + "}" + itemLinks(p.getLinks())
                    + (notBlank(p.getDate()) ? " \\hfill \\textit{" + latexEscape(p.getDate()) + "}" : ""));
            if (notBlank(p.getTech())) hdr.add("\\textit{\\small " + latexEscape(p.getTech()) + "}");
            if (notBlank(skillsLine(p.getSkills()))) hdr.add(skillsLine(p.getSkills()));
            projBlocks.append(entry(hdr, bullets(p.getBullets()))).append(entryGap);
        }
        String projectsSection = projBlocks.length() == 0 ? "" : "\\section*{Projects}\n" + projBlocks;

        // ---- education ----
        List<String> eduParts = new ArrayList<>();
        for (Education e : profile.getEducation()) {
            String school = latexEscape(e.getSchool());
            String loc = latexEscape(e.getLocation());
            String degree = latexEscape(Stream2.join(" | ", e.getDegree(), e.getDetail()));
            String dates = latexEscape(e.getDates());
            eduParts.add("\\textbf{" + school + "} \\hfill " + loc + "\\\\\n\\textit{" + degree + "} \\hfill " + dates);
        }
        String educationSection = eduParts.isEmpty() ? "" :
                "\\section*{Education}\n" + String.join("\\\\[3pt]\n", eduParts);

        String additionalSection = profile.getAdditional().isEmpty() ? "" :
                "\\section*{Additional}\n" + bullets(profile.getAdditional());

        return "\\documentclass[letterpaper," + fontPt + "]{article}\n"
                + "\\usepackage[empty]{fullpage}\n"
                + "\\usepackage[top=" + margin + ",bottom=" + margin + ",left=0.6in,right=0.6in]{geometry}\n"
                + "\\usepackage{enumitem}\n"
                + "\\usepackage[usenames,dvipsnames]{color}\n"
                + "\\definecolor{linkblue}{RGB}{0,90,170}\n"
                + "\\usepackage[colorlinks=true,urlcolor=linkblue,linkcolor=linkblue]{hyperref}\n"
                + "\\usepackage{titlesec}\n"
                + "\\setlength{\\parindent}{0pt}\n"
                + "\\setlist[itemize]{leftmargin=0.2in,topsep=" + topSep + ",itemsep=" + itemSep + ",parsep=0pt}\n"
                // rule sits BELOW the heading with real breathing room (no negative vspace
                // pulling it into the letters), so headings are never struck-through.
                + "\\titleformat{\\section}{\\large\\bfseries\\scshape\\raggedright}{}{0em}{}[\\vspace{" + ruleGap + "}\\titlerule\\vspace{" + ruleGap + "}]\n"
                + "\\titlespacing*{\\section}{0pt}{" + secBefore + "}{" + secAfter + "}\n"
                + "\\newcommand{\\resumeItem}[1]{\\item\\small{#1}}\n"
                + "\\newcommand{\\resumeItemListStart}{\\begin{itemize}}\n"
                + "\\newcommand{\\resumeItemListEnd}{\\end{itemize}}\n"
                + "\\begin{document}\n"
                + "\\begin{center}\n"
                + "{\\LARGE \\textbf{" + latexEscape(notBlank(profile.getName()) ? profile.getName() : "Candidate") + "}}\\\\\n"
                + "\\vspace{3pt}\n"
                + "\\small " + contactLine + "\n"
                + "\\end{center}\n"
                + "\\vspace{-4pt}\n"
                + "\\section*{Professional Summary}\n"
                + latexEscape(profile.getSummary()) + "\n\n"
                + "\\section*{Technical Skills}\n"
                + "\\small " + skillsSection + "\n"
                + "\\section*{Work Experience}\n"
                + expBlocks + "\n"
                + projectsSection + "\n"
                + educationSection + "\n\n"
                + additionalSection + "\n\n"
                + "\\end{document}\n";
    }

    /** \href to a bare URL, showing the URL text; null if blank so it can be filtered out. */
    private String href(String url) {
        if (!notBlank(url)) return null;
        return "\\href{" + absUrl(url) + "}{" + latexEscape(url) + "}";
    }

    /** \href with a custom label (e.g. "Portfolio"). */
    private String labeledHref(String label, String url) {
        if (!notBlank(url)) return null;
        return "\\href{" + absUrl(url) + "}{" + latexEscape(label) + "}";
    }

    /** Per-item " [Live] [GitHub]" links from an Experience/Project links map. */
    private String itemLinks(Map<String, String> links) {
        if (links == null || links.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        String live = firstNonBlank(links.get("live"), links.get("demo"), links.get("website"));
        String gh = links.get("github");
        if (notBlank(live)) sb.append(" \\href{").append(absUrl(live)).append("}{[Live]}");
        if (notBlank(gh)) sb.append(" \\href{").append(absUrl(gh)).append("}{[GitHub]}");
        return sb.toString();
    }

    /** "Skills: a, b, c" line shown under an experience/project (no trailing break —
     *  entry() handles line breaks so no empty line appears before the bullet list). */
    private String skillsLine(List<String> skills) {
        if (skills == null || skills.isEmpty()) return "";
        return "\\textit{\\small Skills:} \\small{" +
                skills.stream().map(this::latexEscape).collect(Collectors.joining(", ")) + "}";
    }

    /** Render one experience/project: header sub-lines then bullets. Crucially the
     *  LAST header line is NOT terminated with "\\\\" (which would inject an empty
     *  line — the visible gap before the bullets); instead it ends the paragraph with
     *  a zero parfillskip group so any \hfill on it still flushes to the right edge. */
    private String entry(List<String> headerLines, String bulletsTex) {
        List<String> lines = headerLines.stream().filter(this::notBlank).collect(Collectors.toList());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i < lines.size() - 1) {
                sb.append(line).append("\\\\\n");
            } else if (line.contains("\\hfill")) {
                // last line has a right-flushed date/location: zero parfillskip so the
                // \hfill flushes to the margin without an empty line before the bullets.
                sb.append("{\\parfillskip=0pt\\relax ").append(line).append("\\par}\n");
            } else {
                // last line is a plain Skills/tech line — end the paragraph normally so it
                // stays LEFT-aligned (parfillskip=0 here would justify it across the full
                // width, spreading the words with huge gaps).
                sb.append(line).append("\\par\n");
            }
        }
        sb.append(bulletsTex).append("\n");
        return sb.toString();
    }

    private String absUrl(String u) {
        return u.startsWith("http") ? u : "https://" + u;
    }

    private String firstNonBlank(String... xs) {
        for (String x : xs) if (notBlank(x)) return x;
        return null;
    }

    private String bullets(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        String body = items.stream().map(x -> "  \\resumeItem{" + latexEscape(x) + "}")
                .collect(Collectors.joining("\n"));
        return "\\resumeItemListStart\n" + body + "\n\\resumeItemListEnd";
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Minimal helper since java.lang.String.join skips null differently than Python's
     * " | ".join(x for x in [...] if x) — keep only non-blank parts. */
    private static class Stream2 {
        static String join(String sep, String... parts) {
            return Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).collect(Collectors.joining(sep));
        }
    }

    /** Compile a Profile to a PDF guaranteed to be ONE page: start at the content-based
     *  squeeze level and tighten the layout until the result fits on a single page (or
     *  the tightest level is reached). Writes to outPdfPath; returns the level used. */
    public int compileProfileToOnePagePdf(Profile profile, String outPdfPath, int timeoutSeconds)
            throws IOException, InterruptedException {
        int start = autoSqueezeLevel(profile);
        int used = start;
        for (int lv = start; lv <= MAX_SQUEEZE; lv++) {
            used = lv;
            compileLatexToPdf(buildLatexTemplate(profile, lv), outPdfPath, timeoutSeconds);
            if (pdfPageCount(Files.readAllBytes(Path.of(outPdfPath))) <= 1) return lv;
        }
        return used; // tightest attempt — still returned so the user gets a PDF
    }

    /** Count pages in a PDF by counting /MediaBox entries (one per page), looking inside
     *  Flate-compressed object streams too (tectonic compresses the page tree). */
    public int pdfPageCount(byte[] pdf) {
        int direct = countOccurrences(pdf, "/MediaBox");
        if (direct > 0) return direct;
        int total = 0;
        String s = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
        int idx = 0;
        while ((idx = s.indexOf("stream", idx)) >= 0) {
            int start = idx + "stream".length();
            if (start < s.length() && s.charAt(start) == '\r') start++;
            if (start < s.length() && s.charAt(start) == '\n') start++;
            int end = s.indexOf("endstream", start);
            if (end < 0) break;
            byte[] chunk = s.substring(start, end).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            try {
                java.util.zip.Inflater inf = new java.util.zip.Inflater();
                inf.setInput(chunk);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] tmp = new byte[8192];
                while (!inf.finished()) {
                    int n = inf.inflate(tmp);
                    if (n == 0) break;
                    bos.write(tmp, 0, n);
                }
                inf.end();
                total += countOccurrences(bos.toByteArray(), "/MediaBox");
            } catch (Exception ignored) {}
            idx = end + "endstream".length();
        }
        return Math.max(1, total);   // assume at least one page if nothing parsed
    }

    private int countOccurrences(byte[] data, String needle) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        int count = 0;
        outer:
        for (int i = 0; i + n.length <= data.length; i++) {
            for (int j = 0; j < n.length; j++) if (data[i + j] != n[j]) continue outer;
            count++;
        }
        return count;
    }

    // ---- LaTeX -> PDF compilation (same external-engine approach as Python: shell
    // out to tectonic/pdflatex, no Java LaTeX engine dependency). ----

    public Optional<String> latexEngine() {
        for (String eng : List.of("tectonic", "pdflatex", "xelatex", "lualatex")) {
            if (isOnPath(eng)) return Optional.of(eng);
        }
        return Optional.empty();
    }

    private boolean isOnPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, exe).canExecute()) return true;
        }
        return false;
    }

    public String compileLatexToPdf(String texCode, String outPdfPath, int timeoutSeconds) throws IOException, InterruptedException {
        String eng = latexEngine().orElseThrow(() ->
                new IllegalStateException("No LaTeX engine found. Install one: brew install tectonic"));
        String code = stripLatexFences(texCode);
        if (!code.contains("\\documentclass")) {
            throw new IllegalArgumentException("That doesn't look like a complete LaTeX resume (no \\documentclass).");
        }
        Path tempDir = Files.createTempDirectory("jobmatch-latex");
        try {
            Path texPath = tempDir.resolve("resume.tex");
            Files.writeString(texPath, code);
            Path pdfTmp = tempDir.resolve("resume.pdf");

            List<String> cmd;
            int runs;
            if (eng.equals("tectonic")) {
                cmd = List.of(eng, "--outdir", tempDir.toString(), "--chatter", "minimal", "--keep-logs", texPath.toString());
                runs = 1;
            } else {
                cmd = List.of(eng, "-interaction=nonstopmode", "-halt-on-error",
                        "-output-directory", tempDir.toString(), texPath.toString());
                runs = 2;
            }
            Process last = null;
            for (int i = 0; i < runs; i++) {
                ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
                last = pb.start();
                if (!last.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                    last.destroyForcibly();
                    throw new IllegalStateException("LaTeX compile timed out");
                }
            }
            if (!Files.exists(pdfTmp)) {
                String log = last != null ? new String(last.getInputStream().readAllBytes()) : "";
                String tail = log.length() > 1800 ? log.substring(log.length() - 1800) : log;
                throw new IllegalStateException(tail.isBlank() ? "LaTeX compile failed with no output." : tail);
            }
            Path outPath = Path.of(outPdfPath);
            Files.createDirectories(outPath.toAbsolutePath().getParent());
            Files.copy(pdfTmp, outPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return outPdfPath;
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private void deleteRecursively(Path dir) {
        try {
            Files.walk(dir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException ignored) {
        }
    }
}
