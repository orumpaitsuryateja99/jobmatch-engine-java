package com.orumpati.jobmatch.web;

import com.orumpati.jobmatch.tracker.Application;
import com.orumpati.jobmatch.tracker.TrackerService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** Mirrors Tab 5 ("Tracker") of app/app.py — record applications you've made, slice
 * by status, and track a daily goal. Persisted to ./data/applications.json. */
@RestController
@RequestMapping("/api/tracker")
public class TrackerController {

    private final TrackerService tracker;

    public TrackerController(TrackerService tracker) {
        this.tracker = tracker;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of(
                "applications", tracker.all(),
                "statuses", TrackerService.STATUSES,
                "appliedToday", tracker.appliedToday());
    }

    @PostMapping
    public Application add(@RequestBody Application app) {
        return tracker.add(app);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Void> updateStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        boolean ok = tracker.updateStatus(id, body.getOrDefault("status", "Applied"));
        return ok ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return tracker.delete(id) ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /** Save the exact résumé PDF used for this application (for interview prep later). */
    @PostMapping(value = "/{id}/resume", consumes = "multipart/form-data")
    public ResponseEntity<Void> uploadResume(@PathVariable String id, @RequestParam("file") MultipartFile file) throws Exception {
        boolean ok = tracker.saveResume(id, file.getBytes(), file.getOriginalFilename());
        return ok ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/{id}/resume")
    public ResponseEntity<byte[]> downloadResume(@PathVariable String id) throws Exception {
        byte[] pdf = tracker.getResume(id);
        if (pdf == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"applied_resume.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    public record StatusList(List<String> statuses) {}
}
