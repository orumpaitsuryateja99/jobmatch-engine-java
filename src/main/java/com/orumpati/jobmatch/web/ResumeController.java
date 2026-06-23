package com.orumpati.jobmatch.web;

import com.orumpati.jobmatch.model.Profile;
import com.orumpati.jobmatch.resume.LatexResumeService;
import com.orumpati.jobmatch.resume.ResumeParser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Mirrors Tab 1 ("Resume") of the Streamlit app: upload a résumé file, parse it
 * into a Profile for THIS request only — nothing is persisted server-side. */
@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private final ResumeParser resumeParser;
    private final LatexResumeService latexResumeService;

    public ResumeController(ResumeParser resumeParser, LatexResumeService latexResumeService) {
        this.resumeParser = resumeParser;
        this.latexResumeService = latexResumeService;
    }

    @PostMapping(value = "/parse", consumes = "multipart/form-data")
    public ResponseEntity<Profile> parse(@RequestParam("file") MultipartFile file) throws Exception {
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "resume";
        Path tmp = Files.createTempFile("upload-", "-" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
        try {
            file.transferTo(tmp);
            Profile profile = resumeParser.parseResume(tmp);
            return ResponseEntity.ok(profile);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Builds the one-page LaTeX template from an already-parsed Profile. */
    @PostMapping("/template")
    public ResponseEntity<String> buildTemplate(@RequestBody Profile profile) {
        return ResponseEntity.ok(latexResumeService.buildLatexTemplate(profile));
    }

    /** Is a LaTeX engine available to compile PDFs? (tectonic / pdflatex / …) */
    @GetMapping("/pdf/status")
    public Map<String, Object> pdfStatus() {
        return Map.of("available", latexResumeService.latexEngine().isPresent(),
                "engine", latexResumeService.latexEngine().orElse(""));
    }

    /** Build the one-page LaTeX résumé from a Profile and compile it to a real PDF
     * with the local LaTeX engine (tectonic), returning the PDF bytes. */
    @PostMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> buildPdf(@RequestBody Profile profile) throws Exception {
        Path out = Files.createTempFile("resume-", ".pdf");
        try {
            // Rule: the generated résumé MUST be one page. Compile, count pages, and
            // tighten the layout until it fits (or we hit the tightest level).
            latexResumeService.compileProfileToOnePagePdf(profile, out.toString(), 60);
            byte[] pdf = Files.readAllBytes(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"resume.pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        } finally {
            Files.deleteIfExists(out);
        }
    }
}
