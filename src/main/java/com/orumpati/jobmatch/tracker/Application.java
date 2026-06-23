package com.orumpati.jobmatch.tracker;

/** One tracked job application — mirrors the Tracker rows in app/tracker.py
 * (company · title · source · status · applied date + the job link). */
public class Application {
    private String id = "";
    private String company = "";
    private String title = "";
    private String jobLink = "";
    private String source = "";
    private String status = "Applied";
    private String appliedDate = "";
    private String notes = "";
    private boolean hasResume = false;
    private String resumeName = "";

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getJobLink() { return jobLink; }
    public void setJobLink(String jobLink) { this.jobLink = jobLink; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAppliedDate() { return appliedDate; }
    public void setAppliedDate(String appliedDate) { this.appliedDate = appliedDate; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isHasResume() { return hasResume; }
    public void setHasResume(boolean hasResume) { this.hasResume = hasResume; }
    public String getResumeName() { return resumeName; }
    public void setResumeName(String resumeName) { this.resumeName = resumeName; }
}
