package com.orumpati.jobmatch.resume;

import java.util.List;

public record AtsMatchResult(int score, List<String> matched, List<String> missing, int total) {}
