package com.orumpati.jobmatch.h1b;

public record H1bStatus(boolean sponsor, String confidence, String label) {
    public static H1bStatus unknown() {
        return new H1bStatus(false, "unknown", "Unknown");
    }
}
