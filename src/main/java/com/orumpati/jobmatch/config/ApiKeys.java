package com.orumpati.jobmatch.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Loads API keys from a local .env file (copied from the Python Job_Automation app)
 * and/or real environment variables. Env vars win over the file. Nothing here is
 * logged or exposed over the API — only boolean "is this provider configured" is
 * surfaced (see /api/sources/status). Mirrors how app/sources.py reads os.getenv. */
@Component
public class ApiKeys {

    private final Map<String, String> values = new HashMap<>();

    @PostConstruct
    void load() {
        // 1) .env file in the working directory (gitignored).
        Path env = Path.of(".env");
        if (Files.exists(env)) {
            try {
                for (String raw : Files.readAllLines(env)) {
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                    int i = line.indexOf('=');
                    String k = line.substring(0, i).strip();
                    String v = line.substring(i + 1).strip();
                    if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);
                    if (!k.isEmpty() && !v.isEmpty()) values.put(k, v);
                }
            } catch (Exception ignore) {
                // missing/unreadable .env is fine — providers just stay "not configured"
            }
        }
        // 2) Real environment variables override the file.
        System.getenv().forEach((k, v) -> { if (v != null && !v.isBlank()) values.put(k, v); });
    }

    public String get(String key) {
        return values.getOrDefault(key, "");
    }

    public boolean has(String... keys) {
        for (String k : keys) {
            if (!get(k).isBlank()) continue;
            return false;
        }
        return true;
    }

    public boolean hasAny(String... keys) {
        for (String k : keys) if (!get(k).isBlank()) return true;
        return false;
    }
}
