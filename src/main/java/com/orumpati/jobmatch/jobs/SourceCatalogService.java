package com.orumpati.jobmatch.jobs;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Parses config/target_companies.txt (copied from the Python app, verified live
 * boards) into the no-key ATS sources the Java fetchers support. */
@Service
public class SourceCatalogService {

    public record Board(String ats, String token, String name) {}

    private final List<Board> boards = new ArrayList<>();

    public SourceCatalogService() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource("data/target_companies.txt").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#")) continue;
                String[] p = s.split(",", 3);
                if (p.length < 2) continue;
                String ats = p[0].strip().toLowerCase();
                if (!Set.of("greenhouse", "lever", "ashby", "workday", "smartrecruiters").contains(ats)) continue;
                String token = p[1].strip();
                String name = p.length >= 3 ? p[2].strip() : token;
                boards.add(new Board(ats, token, name));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load target_companies.txt", e);
        }
    }

    public List<Board> boards() {
        return List.copyOf(boards);
    }

    public long count(String ats) {
        return boards.stream().filter(b -> b.ats().equals(ats)).count();
    }
}
