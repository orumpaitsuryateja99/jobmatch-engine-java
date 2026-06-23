package com.orumpati.jobmatch.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Mirrors the `profile` dict shape from app/resume_parser.py. */
public class Profile {
    private String name = "Candidate";
    private String email;
    private String phone;
    private Map<String, String> links = new HashMap<>();
    private String summary = "";
    private List<SkillCategory> skillCategories = new ArrayList<>();
    private List<String> skillPhrases = new ArrayList<>();
    private List<String> skills = new ArrayList<>(new TreeSet<>());
    private List<String> tools = new ArrayList<>();
    private List<String> domains = new ArrayList<>();
    private List<Project> projects = new ArrayList<>();
    private List<Experience> experience = new ArrayList<>();
    private List<Education> education = new ArrayList<>();
    private List<String> additional = new ArrayList<>();
    private List<String> targetRoles = new ArrayList<>(List.of(
            "software engineer", "backend engineer", "full stack engineer"));
    private int experienceYears = 0;
    private String rawText = "";
    private String latexTemplate = "";
    private String resumeFile = "";

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Map<String, String> getLinks() { return links; }
    public void setLinks(Map<String, String> links) { this.links = links; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public List<SkillCategory> getSkillCategories() { return skillCategories; }
    public void setSkillCategories(List<SkillCategory> skillCategories) { this.skillCategories = skillCategories; }
    public List<String> getSkillPhrases() { return skillPhrases; }
    public void setSkillPhrases(List<String> skillPhrases) { this.skillPhrases = skillPhrases; }
    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }
    public List<String> getTools() { return tools; }
    public void setTools(List<String> tools) { this.tools = tools; }
    public List<String> getDomains() { return domains; }
    public void setDomains(List<String> domains) { this.domains = domains; }
    public List<Project> getProjects() { return projects; }
    public void setProjects(List<Project> projects) { this.projects = projects; }
    public List<Experience> getExperience() { return experience; }
    public void setExperience(List<Experience> experience) { this.experience = experience; }
    public List<Education> getEducation() { return education; }
    public void setEducation(List<Education> education) { this.education = education; }
    public List<String> getAdditional() { return additional; }
    public void setAdditional(List<String> additional) { this.additional = additional; }
    public List<String> getTargetRoles() { return targetRoles; }
    public void setTargetRoles(List<String> targetRoles) { this.targetRoles = targetRoles; }
    public int getExperienceYears() { return experienceYears; }
    public void setExperienceYears(int experienceYears) { this.experienceYears = experienceYears; }
    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }
    public String getLatexTemplate() { return latexTemplate; }
    public void setLatexTemplate(String latexTemplate) { this.latexTemplate = latexTemplate; }
    public String getResumeFile() { return resumeFile; }
    public void setResumeFile(String resumeFile) { this.resumeFile = resumeFile; }
}
