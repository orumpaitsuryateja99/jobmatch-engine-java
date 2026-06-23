package com.orumpati.jobmatch.ats;

import java.util.List;
import java.util.Map;

public record AtsResult(int score, String band, Map<String, Double> components,
                         List<String> missingSkills, List<String> missingTools,
                         List<String> missingKeywords, List<String> matchedSkills) {}
