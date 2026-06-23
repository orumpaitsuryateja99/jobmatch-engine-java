package com.orumpati.jobmatch.web;

import com.orumpati.jobmatch.h1b.H1bService;
import com.orumpati.jobmatch.h1b.H1bStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** H1B-CRITICAL: confidence is a heuristic from a curated 342-company list, NOT a
 * guarantee. Always verify on MyVisaJobs / H1BGrader before applying. */
@RestController
public class H1bController {

    private final H1bService h1bService;

    public H1bController(H1bService h1bService) {
        this.h1bService = h1bService;
    }

    @GetMapping("/api/h1b/status")
    public Map<String, Object> status(@RequestParam String company) {
        H1bStatus status = h1bService.status(company);
        return Map.of(
                "company", company,
                "sponsor", status.sponsor(),
                "confidence", status.confidence(),
                "label", status.label(),
                "badge", h1bService.badge(status));
    }
}
