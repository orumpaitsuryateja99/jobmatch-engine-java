package com.orumpati.jobmatch.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Project {
    private String name;
    private String subtitle = "";
    private String date = "";
    private String tech = "";
    private Map<String, String> links = new HashMap<>();
    private boolean hasLinkMarkers;
    private List<String> bullets = new ArrayList<>();
    private List<String> skills = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public String getTech() { return tech; }
    public void setTech(String tech) { this.tech = tech; }
    public Map<String, String> getLinks() { return links; }
    public void setLinks(Map<String, String> links) { this.links = links; }
    public boolean isHasLinkMarkers() { return hasLinkMarkers; }
    public void setHasLinkMarkers(boolean hasLinkMarkers) { this.hasLinkMarkers = hasLinkMarkers; }
    public List<String> getBullets() { return bullets; }
    public void setBullets(List<String> bullets) { this.bullets = bullets; }
    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }
}
