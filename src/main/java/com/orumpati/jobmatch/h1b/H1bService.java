package com.orumpati.jobmatch.h1b;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Java port of app/h1b.py — structured H1B sponsor lookup with confidence.
 *
 * Matches the normalized company name EXACTLY against config/h1b_sponsors.json
 * aliases (no substring matching, so "unit" never matches "United").
 *
 * Confidence is a heuristic from a curated list, NOT a guarantee — always verify
 * on MyVisaJobs / H1BGrader before applying. */
@Service
public class H1bService {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private final Map<String, String> index = new HashMap<>();

    public H1bService() {
        try (InputStream in = new ClassPathResource("data/h1b_sponsors.json").getInputStream()) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(in);
            for (JsonNode entry : root.path("sponsors")) {
                String conf = entry.path("confidence").asText("medium");
                Set<String> keys = new HashSet<>();
                for (JsonNode alias : entry.path("aliases")) keys.add(alias.asText(""));
                keys.add(entry.path("name").asText(""));
                for (String k : keys) {
                    String nk = normalize(k);
                    if (!nk.isEmpty() && !"high".equals(index.get(nk))) {
                        index.put(nk, conf);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load h1b_sponsors.json", e);
        }
    }

    private static String normalize(String s) {
        return NON_ALNUM.matcher(s == null ? "" : s.toLowerCase()).replaceAll("");
    }

    public H1bStatus status(String company) {
        String norm = normalize(company);
        if (norm.isEmpty()) return H1bStatus.unknown();
        String conf = index.get(norm);
        if (conf != null) {
            String label = switch (conf) {
                case "high" -> "Verified";
                case "medium" -> "Likely";
                default -> "Likely";
            };
            return new H1bStatus(true, conf, label);
        }
        return H1bStatus.unknown();
    }

    public String badge(H1bStatus status) {
        if ("high".equals(status.confidence())) return "✅ H1B (verified)";
        if ("medium".equals(status.confidence())) return "🟢 H1B (likely)";
        return "⚠️ verify H1B";
    }

    public int sponsorCount() {
        return index.size();
    }
}
