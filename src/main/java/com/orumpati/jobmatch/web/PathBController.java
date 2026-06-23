package com.orumpati.jobmatch.web;

import com.orumpati.jobmatch.jobs.PathBService;
import com.orumpati.jobmatch.jobs.PathBService.ImportResult;
import com.orumpati.jobmatch.model.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Path B — AI Search → Paste JSON. Build the CO-STAR prompt the user runs in any
 * AI with web search, then import the JSON they paste back. Mirrors the Path B block
 * of app/app.py (no server-side LLM call). */
@RestController
@RequestMapping("/api/pathb")
public class PathBController {

    private final PathBService pathB;

    public PathBController(PathBService pathB) {
        this.pathB = pathB;
    }

    @PostMapping("/prompt")
    public Map<String, String> prompt(@RequestBody PromptRequest req) {
        String text = pathB.buildSearchPrompt(req.profile(), req.prefs(), req.location(),
                req.maxYears(), req.roleLabels(), req.workMode(), req.h1bOnly(), req.freshnessLabel());
        return Map.of("prompt", text);
    }

    @PostMapping("/import")
    public ImportResult importPasted(@RequestBody ImportRequest req) {
        return pathB.normalizePastedJobs(req.text(), req.newGradOnly(), req.maxYears(), req.focusKeys());
    }

    public record PromptRequest(Profile profile, String prefs, String location, int maxYears,
                                List<String> roleLabels, String workMode, boolean h1bOnly,
                                String freshnessLabel) {}
    public record ImportRequest(String text, boolean newGradOnly, int maxYears, List<String> focusKeys) {}
}
