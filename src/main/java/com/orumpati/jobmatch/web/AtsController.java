package com.orumpati.jobmatch.web;

import com.orumpati.jobmatch.ats.AtsResult;
import com.orumpati.jobmatch.ats.AtsScorer;
import com.orumpati.jobmatch.model.JobPosting;
import com.orumpati.jobmatch.model.Profile;
import com.orumpati.jobmatch.resume.AtsMatchResult;
import com.orumpati.jobmatch.resume.LatexResumeService;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;

/** Mirrors Tab 3 ("Match & Score") — score a parsed résumé Profile against a raw
 * job description using the same weighted ATS formula as app/ats.py. */
@RestController
@RequestMapping("/api/ats")
public class AtsController {

    private final AtsScorer atsScorer;
    private final LatexResumeService latexResumeService;

    public AtsController(AtsScorer atsScorer, LatexResumeService latexResumeService) {
        this.atsScorer = atsScorer;
        this.latexResumeService = latexResumeService;
    }

    @PostMapping("/score")
    public AtsResult score(@RequestBody ScoreRequest req) {
        return atsScorer.scoreResumeVsJd(req.profile(), req.jdText(), req.title());
    }

    /** Batch-score every job from a Find Jobs run against the parsed résumé, scored
     * highest-first — powers Tab 3 ("Match & Score") without a per-card round-trip. */
    @PostMapping("/score-jobs")
    public List<ScoredJob> scoreJobs(@RequestBody ScoreJobsRequest req) {
        return req.jobs().stream()
                .filter(j -> j.getError() == null)
                .map(j -> {
                    AtsResult r = atsScorer.scoreResumeVsJd(req.profile(), j.getDescription(), j.getTitle());
                    return new ScoredJob(j, r.score(), r.band(), r.matchedSkills(), r.missingSkills());
                })
                .sorted(Comparator.comparingInt(ScoredJob::score).reversed())
                .toList();
    }

    /** Keyword-coverage check for a tailored LaTeX resume against a JD — mirrors
     * compute_latex_ats_match() in app/latex_resume.py. */
    @PostMapping("/latex-match")
    public AtsMatchResult latexMatch(@RequestBody LatexMatchRequest req) {
        return latexResumeService.computeLatexAtsMatch(req.latex(), req.jdText());
    }

    public record ScoreRequest(Profile profile, String jdText, String title) {}
    public record LatexMatchRequest(String latex, String jdText) {}
    public record ScoreJobsRequest(Profile profile, List<JobPosting> jobs) {}
    public record ScoredJob(JobPosting job, int score, String band,
                            List<String> matched, List<String> missing) {}
}
