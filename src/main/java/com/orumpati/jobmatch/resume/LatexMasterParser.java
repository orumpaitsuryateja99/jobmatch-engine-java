package com.orumpati.jobmatch.resume;

import com.orumpati.jobmatch.model.Education;
import com.orumpati.jobmatch.model.Experience;
import com.orumpati.jobmatch.model.Profile;
import com.orumpati.jobmatch.model.Project;
import com.orumpati.jobmatch.model.SkillCategory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Precise parser for résumés written with the common custom-macro LaTeX template
 * (\resumeHeading, \resumeProject, \resumeItem, \section, \resumeItemListStart).
 *
 * The generic tex→text path collapses those macros and DROPS \resumeItem content,
 * so experience/projects come out empty or mangled. When the uploaded .tex uses
 * these macros, we parse them directly from the source instead — giving correct,
 * fully-populated sections (every role with its bullets, every project named).
 */
@Service
public class LatexMasterParser {

    private static final Pattern HREF = Pattern.compile("\\\\href\\{([^}]*)\\}\\{([^}]*)\\}");
    private static final Pattern DATE_RANGE = Pattern.compile(
            "\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\w*\\.?\\s+\\d{4}\\b"
            + "|\\b\\d{4}\\s*[-–]\\s*(?:present|\\d{4})\\b|\\bpresent\\b|^\\s*\\d{4}\\s*$",
            Pattern.CASE_INSENSITIVE);

    private boolean looksLikeDate(String s) {
        return s != null && DATE_RANGE.matcher(s).find();
    }

    /** Does this .tex use the custom-macro template we can parse precisely? */
    public boolean looksLikeMacroResume(String tex) {
        return tex != null && (tex.contains("\\resumeHeading") || tex.contains("\\resumeProject")
                || tex.contains("\\resumeItem"));
    }

    /** Override the profile's structured sections with macro-accurate ones. */
    public void enrich(Profile p, String tex) {
        if (!looksLikeMacroResume(tex)) return;
        String nm = extractName(tex);
        if (nm != null && !nm.isBlank()) p.setName(nm);
        List<Sec> secs = splitSections(tex);
        for (Sec s : secs) {
            String n = s.title.toLowerCase(Locale.ROOT);
            if (n.contains("experience") || n.contains("employment")) {
                List<Experience> ex = parseExperiences(s.body);
                if (!ex.isEmpty()) p.setExperience(ex);
            } else if (n.contains("project")) {
                List<Project> pr = parseProjects(s.body);
                if (!pr.isEmpty()) p.setProjects(pr);
            } else if (n.contains("education")) {
                List<Education> ed = parseEducation(s.body);
                if (!ed.isEmpty()) p.setEducation(ed);
            } else if (n.contains("summary") || n.contains("objective") || n.contains("profile")) {
                String sum = cleanTex(s.body);
                if (!sum.isBlank()) p.setSummary(sum);
            } else if (n.contains("additional") || n.contains("achievement") || n.contains("award")) {
                List<String> add = parseItems(s.body);
                if (!add.isEmpty()) p.setAdditional(add);
            } else if (n.contains("skill")) {
                List<SkillCategory> sc = parseSkillCategories(s.body);
                if (!sc.isEmpty()) p.setSkillCategories(sc);
            }
        }
    }

    /* ---------------- name (from the centered header) ---------------- */

    private static final List<Pattern> NAME_PATTERNS = List.of(
            Pattern.compile("\\\\(?:LARGE|Large|huge|Huge)\\s*\\\\scshape\\s+([A-Z][^\\\\{}]+)"),
            Pattern.compile("\\\\(?:LARGE|Large|huge|Huge)\\s*\\\\textbf\\{([^}]+)\\}"),
            Pattern.compile("\\\\textbf\\{\\\\(?:LARGE|Large|huge|Huge)\\s+([^\\\\{}]+)\\}"),
            Pattern.compile("\\\\scshape\\s+([A-Z][A-Za-z.'\\-]+(?:\\s+[A-Z][A-Za-z.'\\-]+)+)"));

    private String extractName(String tex) {
        // Look inside the \begin{center}…\end{center} header block (the name lives there).
        // Don't cut at the first "\section" — that matches \titleformat{\section} in the preamble.
        String header = tex;
        int cs = tex.indexOf("\\begin{center}");
        if (cs >= 0) {
            int ce = tex.indexOf("\\end{center}", cs);
            header = tex.substring(cs, ce > 0 ? ce : Math.min(tex.length(), cs + 400));
        }
        for (Pattern pat : NAME_PATTERNS) {
            Matcher m = pat.matcher(header);
            if (m.find()) {
                String n = cleanTex(m.group(1));
                if (!n.isBlank() && n.length() <= 60) return n;
            }
        }
        return null;
    }

    /* ---------------- sections ---------------- */

    private record Sec(String title, String body) {}

    private List<Sec> splitSections(String tex) {
        List<Sec> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\\\section\\*?\\{([^}]*)\\}").matcher(tex);
        List<int[]> spans = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (m.find()) { spans.add(new int[]{m.start(), m.end()}); titles.add(cleanTex(m.group(1))); }
        for (int i = 0; i < spans.size(); i++) {
            int bodyStart = spans.get(i)[1];
            int bodyEnd = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : tex.length();
            String body = tex.substring(bodyStart, bodyEnd);
            // stop at \end{document} if present in the trailing section
            int endDoc = body.indexOf("\\end{document}");
            if (endDoc >= 0) body = body.substring(0, endDoc);
            out.add(new Sec(titles.get(i), body));
        }
        return out;
    }

    /* ---------------- experiences ---------------- */

    private List<Experience> parseExperiences(String body) {
        List<Experience> out = new ArrayList<>();
        List<Token> toks = tokenize(body);
        Experience cur = null;
        for (Token t : toks) {
            if (t.kind == Kind.HEADING) {
                // \resumeHeading{role+links}{location}{company}{date}
                cur = new Experience();
                String roleArg = arg(t.args, 0);
                cur.setRole(stripLinkLabels(cleanTex(roleArg)));
                cur.setCompany(cleanTex(arg(t.args, 2)));
                // The two right-column args (1 and 3) hold date + location, but the
                // template puts them in inconsistent order — pick by date pattern.
                String a1 = cleanTex(arg(t.args, 1));
                String a3 = cleanTex(arg(t.args, 3));
                if (looksLikeDate(a3)) { cur.setDate(a3); cur.setLocation(a1); }
                else if (looksLikeDate(a1)) { cur.setDate(a1); cur.setLocation(a3); }
                else { cur.setLocation(a1); cur.setDate(a3); }
                cur.setLinks(extractLinks(roleArg));
                out.add(cur);
            } else if (t.kind == Kind.ITEM && cur != null) {
                String b = cleanTex(arg(t.args, 0));
                if (!b.isBlank()) cur.getBullets().add(b);
            }
        }
        return out;
    }

    /* ---------------- projects ---------------- */

    private List<Project> parseProjects(String body) {
        List<Project> out = new ArrayList<>();
        List<Token> toks = tokenize(body);
        Project cur = null;
        for (Token t : toks) {
            if (t.kind == Kind.PROJECT) {
                // \resumeProject{name|subtitle + links}{date}{tech}
                cur = new Project();
                String nameArg = arg(t.args, 0);
                String nameClean = stripLinkLabels(cleanTex(nameArg));
                String name = nameClean.contains("|") ? nameClean.substring(0, nameClean.indexOf('|')).strip() : nameClean;
                String subtitle = nameClean.contains("|") ? nameClean.substring(nameClean.indexOf('|') + 1).strip() : "";
                cur.setName(name);
                cur.setSubtitle(subtitle);
                cur.setDate(cleanTex(arg(t.args, 1)));
                cur.setTech(cleanTex(arg(t.args, 2)));
                cur.setLinks(extractLinks(nameArg));
                out.add(cur);
            } else if (t.kind == Kind.HEADING) {
                // some templates use \resumeHeading for projects too
                cur = new Project();
                String nameArg = arg(t.args, 0);
                String nameClean = stripLinkLabels(cleanTex(nameArg));
                cur.setName(nameClean.contains("|") ? nameClean.substring(0, nameClean.indexOf('|')).strip() : nameClean);
                cur.setTech(cleanTex(arg(t.args, 2)));
                cur.setDate(cleanTex(arg(t.args, 3)));
                cur.setLinks(extractLinks(nameArg));
                out.add(cur);
            } else if (t.kind == Kind.ITEM && cur != null) {
                String b = cleanTex(arg(t.args, 0));
                if (!b.isBlank()) cur.getBullets().add(b);
            }
        }
        out.removeIf(pr -> pr.getName() == null || pr.getName().isBlank());
        return out;
    }

    /* ---------------- education ---------------- */

    private List<Education> parseEducation(String body) {
        List<Education> out = new ArrayList<>();
        for (Token t : tokenize(body)) {
            if (t.kind == Kind.HEADING) {
                Education e = new Education();
                e.setSchool(cleanTex(arg(t.args, 0)));
                e.setLocation(cleanTex(arg(t.args, 1)));
                String deg = cleanTex(arg(t.args, 2));
                e.setDegree(deg.contains("|") ? deg.substring(0, deg.indexOf('|')).strip() : deg);
                e.setDetail(deg.contains("|") ? deg.substring(deg.indexOf('|') + 1).strip() : "");
                e.setDates(cleanTex(arg(t.args, 3)));
                if (!e.getSchool().isBlank()) out.add(e);
            }
        }
        return out;
    }

    /* ---------------- additional (\item lines) ---------------- */

    private List<String> parseItems(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\\\item\\b").matcher(body);
        int[] starts = m.results().mapToInt(r -> r.end()).toArray();
        for (int i = 0; i < starts.length; i++) {
            int from = starts[i];
            int to = (i + 1 < starts.length) ? body.indexOf("\\item", from) : body.length();
            if (to < 0) to = body.length();
            String chunk = body.substring(from, to);
            int endList = chunk.indexOf("\\end{");
            if (endList >= 0) chunk = chunk.substring(0, endList);
            String c = cleanTex(chunk);
            if (c.length() >= 10) out.add(c);
        }
        return out;
    }

    /* ---------------- technical skills (\item \textbf{Cat:} items) ---------------- */

    private List<SkillCategory> parseSkillCategories(String body) {
        List<SkillCategory> out = new ArrayList<>();
        for (String raw : parseItems(body)) {
            int idx = raw.indexOf(':');
            if (idx <= 0) continue;
            String cat = raw.substring(0, idx).strip();
            String rest = raw.substring(idx + 1);
            List<String> items = splitCsvHonoringParens(rest);
            if (!cat.isBlank() && !items.isEmpty()) out.add(new SkillCategory(cat, items));
        }
        return out;
    }

    private List<String> splitCsvHonoringParens(String text) {
        List<String> items = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int depth = 0;
        for (char ch : (text == null ? "" : text).toCharArray()) {
            if (ch == '(') depth++;
            else if (ch == ')' && depth > 0) depth--;
            if (ch == ',' && depth == 0) {
                String it = buf.toString().strip();
                if (!it.isEmpty()) items.add(it);
                buf.setLength(0);
            } else buf.append(ch);
        }
        String it = buf.toString().strip();
        if (!it.isEmpty()) items.add(it);
        return items;
    }

    /* ---------------- macro tokenizer (balanced braces) ---------------- */

    private enum Kind { HEADING, PROJECT, ITEM }

    private record Token(Kind kind, List<String> args) {}

    private List<Token> tokenize(String body) {
        List<Token> out = new ArrayList<>();
        int i = 0, n = body.length();
        while (i < n) {
            int h = body.indexOf("\\resumeHeading", i);
            int p = body.indexOf("\\resumeProject", i);
            int it = body.indexOf("\\resumeItem", i);
            // \resumeItemListStart/End also start with \resumeItem; exclude them
            while (it >= 0 && body.startsWith("\\resumeItemList", it)) {
                it = body.indexOf("\\resumeItem", it + "\\resumeItemList".length());
            }
            int next = min3(h, p, it);
            if (next < 0) break;
            if (next == h) {
                var r = readArgs(body, next + "\\resumeHeading".length(), 4);
                out.add(new Token(Kind.HEADING, r.args)); i = r.end;
            } else if (next == p) {
                var r = readArgs(body, next + "\\resumeProject".length(), 3);
                out.add(new Token(Kind.PROJECT, r.args)); i = r.end;
            } else {
                var r = readArgs(body, next + "\\resumeItem".length(), 1);
                out.add(new Token(Kind.ITEM, r.args)); i = r.end;
            }
        }
        return out;
    }

    private int min3(int a, int b, int c) {
        int m = Integer.MAX_VALUE;
        if (a >= 0) m = Math.min(m, a);
        if (b >= 0) m = Math.min(m, b);
        if (c >= 0) m = Math.min(m, c);
        return m == Integer.MAX_VALUE ? -1 : m;
    }

    private record Args(List<String> args, int end) {}

    /** Read up to n balanced-brace {..} arguments starting at idx, skipping any
     *  whitespace/newlines (and inter-arg spacing macros like \;) between them. */
    private Args readArgs(String s, int idx, int n) {
        List<String> args = new ArrayList<>();
        int i = idx;
        for (int a = 0; a < n; a++) {
            while (i < s.length() && s.charAt(i) != '{') {
                char c = s.charAt(i);
                if (!Character.isWhitespace(c) && c != '\\' && c != ';' && c != ',') break;
                i++;
            }
            if (i >= s.length() || s.charAt(i) != '{') break;
            int depth = 0, start = i + 1;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) break; }
                i++;
            }
            args.add(s.substring(start, Math.min(i, s.length())));
            i++; // past closing brace
        }
        return new Args(args, i);
    }

    private String arg(List<String> args, int i) {
        return (args != null && i < args.size()) ? args.get(i) : "";
    }

    /* ---------------- text cleaning ---------------- */

    private Map<String, String> extractLinks(String macroArg) {
        Map<String, String> links = new LinkedHashMap<>();
        Matcher m = HREF.matcher(macroArg == null ? "" : macroArg);
        while (m.find()) {
            String url = m.group(1).strip();
            String label = m.group(2).toLowerCase(Locale.ROOT);
            if (label.contains("github")) links.putIfAbsent("github", url);
            else if (label.contains("live") || label.contains("demo") || label.contains("website"))
                links.putIfAbsent("live", url);
        }
        return links;
    }

    /** Drop "[Live]" / "[GitHub]" style bracket labels left in a name/role field. */
    private String stripLinkLabels(String s) {
        return s.replaceAll("\\[[^\\]]{0,18}\\]", "").replaceAll("\\s+", " ").strip();
    }

    /** Turn a LaTeX fragment into clean readable text. */
    String cleanTex(String s) {
        if (s == null) return "";
        String t = s;
        t = HREF.matcher(t).replaceAll("$2");                         // \href{url}{label} -> label
        t = t.replaceAll("\\\\(textbf|textit|emph|underline|texttt|textsc)\\b", " "); // style cmds: keep braces' content
        t = t.replace("\\textbar{}", "|").replace("\\textbar", "|");
        t = t.replace("\\&", "&").replace("\\%", "%").replace("\\_", "_")
             .replace("\\#", "#").replace("\\$", "$");
        t = t.replaceAll("\\\\['`^\"=.~]\\{?([a-zA-Z])\\}?", "$1");   // accents: \'{e} -> e
        t = t.replace("\\par", " ").replace("\\;", " ").replace("\\,", " ").replace("\\!", " ")
             .replace("\\&", "&");
        t = t.replaceAll("\\\\[a-zA-Z@]+\\b", " ");                   // remaining commands
        t = t.replaceAll("[{}$]", " ");                              // braces + math delimiters
        t = t.replaceAll("\\s+", " ").strip();
        // trim dangling separators left by removed links
        t = t.replaceAll("\\s*[|·]\\s*$", "").strip();
        return t;
    }
}
