package com.orumpati.jobmatch.ats;

import com.orumpati.jobmatch.model.Education;
import com.orumpati.jobmatch.model.Experience;
import com.orumpati.jobmatch.model.Profile;
import com.orumpati.jobmatch.model.Project;
import com.orumpati.jobmatch.resume.LatexResumeService;
import com.orumpati.jobmatch.text.TextUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Java port of app/ats.py — ATS match scoring.
 *
 * ATS_score = 0.33*hard_skill + 0.27*keyword + 0.15*title + 0.10*tools
 *           + 0.05*experience + 0.05*domain + 0.05*formatting
 *
 * An ESTIMATE to prioritize effort, not the employer's real ATS number. */
@Service
public class AtsScorer {

    private final LatexResumeService latexResumeService;

    @Autowired
    public AtsScorer(LatexResumeService latexResumeService) {
        this.latexResumeService = latexResumeService;
    }

    private static final Map<String, Double> WEIGHTS = Map.of(
            "hard_skill", 0.33, "keyword", 0.27, "title", 0.15, "tools", 0.10,
            "experience", 0.05, "domain", 0.05, "formatting", 0.05);

    private double pct(Set<String> have, Set<String> need) {
        if (need.isEmpty()) return 100.0;
        long inter = need.stream().filter(have::contains).count();
        return 100.0 * inter / need.size();
    }

    public JobSignals parseJob(String jdText, String title) {
        String t = (title != null && !title.isBlank()) ? title : firstLine(jdText);
        Set<String> keywords = latexResumeService.extractJdKeywords(jdText).stream()
                .map(String::toLowerCase).collect(Collectors.toCollection(LinkedHashSet::new));
        return new JobSignals(t, TextUtils.detectSkills(jdText), TextUtils.detectTools(jdText),
                TextUtils.detectDomains(jdText), keywords, TextUtils.extractYearsRequired(jdText), jdText);
    }

    private String firstLine(String text) {
        if (text == null) return "";
        for (String line : text.split("\n")) {
            if (!line.strip().isEmpty()) return line.strip();
        }
        return "";
    }

    private double titleAlignment(String jdTitle, List<String> targetRoles) {
        Set<String> jdTokens = tokens(TextUtils.normalize(jdTitle));
        if (jdTokens.isEmpty()) return 50.0;
        double best = 0.0;
        for (String role : targetRoles) {
            Set<String> rt = tokens(TextUtils.normalize(role));
            if (rt.isEmpty()) continue;
            long overlap = rt.stream().filter(jdTokens::contains).count();
            best = Math.max(best, (double) overlap / rt.size());
        }
        if (TextUtils.isSeniorTitle(jdTitle)) best *= 0.3;
        return Math.round(best * 1000) / 10.0;
    }

    private static final Pattern WORD_ONLY = Pattern.compile("[a-z]+");

    private Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        Matcher m = WORD_ONLY.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    private double experienceFit(int yearsRequired) {
        if (yearsRequired <= 1) return 100.0;
        if (yearsRequired == 2) return 90.0;
        if (yearsRequired == 3) return 60.0;
        if (yearsRequired == 4) return 35.0;
        return 20.0;
    }

    private double formattingReadiness(Profile profile) {
        String raw = profile.getRawText() == null ? "" : profile.getRawText().toLowerCase();
        if (raw.isEmpty()) return 90.0;
        double score = 100.0;
        long hasSections = Stream.of("experience", "education", "skills", "projects")
                .filter(raw::contains).count();
        if (hasSections < 2) score -= 30;
        if (countOccurrences(raw, '\t') > 40) score -= 15;
        return Math.max(0.0, Math.min(100.0, score));
    }

    private long countOccurrences(String s, char c) {
        return s.chars().filter(ch -> ch == c).count();
    }

    private String profileText(Profile profile) {
        List<String> parts = new ArrayList<>();
        parts.add(profile.getSummary());
        for (Project p : profile.getProjects()) {
            parts.add(p.getName());
            parts.addAll(p.getBullets());
        }
        for (Experience e : profile.getExperience()) {
            parts.add(e.getCompany());
            parts.addAll(e.getBullets());
        }
        parts.addAll(profile.getSkills());
        parts.addAll(profile.getTools());
        if (profile.getRawText() != null && !profile.getRawText().isBlank()) parts.add(profile.getRawText());
        return parts.stream().filter(p -> p != null && !p.isBlank()).collect(Collectors.joining(" "));
    }

    public Set<String> profileKeywordPool(Profile profile) {
        Set<String> out = new HashSet<>(TextUtils.contentTokens(profileText(profile)));
        out.addAll(profile.getSkills());
        out.addAll(profile.getTools());
        return out;
    }

    public AtsResult atsScore(Profile profile, JobSignals job, List<String> targetRolesOverride, Set<String> profileKeywords) {
        Set<String> mySkills = new HashSet<>(profile.getSkills());
        Set<String> myTools = new HashSet<>(profile.getTools());
        Set<String> myDomains = new HashSet<>(profile.getDomains());
        Set<String> myKeywords = profileKeywords != null ? profileKeywords : profileKeywordPool(profile);

        List<String> rolesForTitle = (targetRolesOverride != null && !targetRolesOverride.isEmpty())
                ? targetRolesOverride : profile.getTargetRoles();

        Map<String, Double> comp = new LinkedHashMap<>();
        comp.put("hard_skill", round1(pct(mySkills, job.skills())));
        comp.put("keyword", round1(pct(myKeywords, job.keywords())));
        comp.put("title", titleAlignment(job.title(), rolesForTitle));
        comp.put("tools", round1(pct(myTools, job.tools())));
        comp.put("experience", experienceFit(job.yearsRequired()));
        boolean domainOverlap = myDomains.stream().anyMatch(job.domains()::contains);
        comp.put("domain", domainOverlap ? 100.0 : (job.domains().isEmpty() ? 75.0 : 50.0));
        comp.put("formatting", formattingReadiness(profile));

        double overall = comp.entrySet().stream()
                .mapToDouble(e -> e.getValue() * WEIGHTS.get(e.getKey())).sum();

        List<String> missingSkills = job.skills().stream().filter(s -> !mySkills.contains(s)).sorted().toList();
        List<String> missingTools = job.tools().stream().filter(t -> !myTools.contains(t)).sorted().toList();
        List<String> missingKeywords = job.keywords().stream().filter(k -> !myKeywords.contains(k))
                .sorted().limit(12).toList();
        List<String> matchedSkills = mySkills.stream().filter(job.skills()::contains).sorted().toList();

        return new AtsResult(Math.round((float) overall), band(overall), comp,
                missingSkills, missingTools, missingKeywords, matchedSkills);
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    public String band(double score) {
        if (score >= 85) return "Strong";
        if (score >= 70) return "Good";
        if (score >= 55) return "Stretch";
        return "Weak";
    }

    public AtsResult scoreResumeVsJd(Profile profile, String jdText, String title) {
        return atsScore(profile, parseJob(jdText, title), null, null);
    }
}
