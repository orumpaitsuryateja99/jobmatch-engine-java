package com.orumpati.jobmatch.model;

import java.util.ArrayList;
import java.util.List;

public class SkillCategory {
    private String category;
    private List<String> items = new ArrayList<>();

    public SkillCategory() {}

    public SkillCategory(String category, List<String> items) {
        this.category = category;
        this.items = items;
    }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public List<String> getItems() { return items; }
    public void setItems(List<String> items) { this.items = items; }
}
