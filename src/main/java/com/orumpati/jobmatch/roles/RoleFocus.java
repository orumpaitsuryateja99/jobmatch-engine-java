package com.orumpati.jobmatch.roles;

import java.util.List;

public record RoleFocus(String key, String label, List<String> hints,
                         List<String> targetRoles, List<String> coreSkills) {}
