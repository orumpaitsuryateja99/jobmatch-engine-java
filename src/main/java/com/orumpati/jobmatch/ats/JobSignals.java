package com.orumpati.jobmatch.ats;

import java.util.Set;

public record JobSignals(String title, Set<String> skills, Set<String> tools,
                          Set<String> domains, Set<String> keywords,
                          int yearsRequired, String raw) {}
