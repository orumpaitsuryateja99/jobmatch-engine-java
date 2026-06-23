package com.orumpati.jobmatch.model;

/** Normalized job dict, mirrors the {title, company, location, job_link, source,
 * description, work_mode, posted_date} shape every app/sources.py fetcher returns. */
public class JobPosting {
    private String title = "";
    private String company = "";
    private String boardToken = "";
    private String location = "";
    private String jobLink = "";
    private String source = "";
    private String description = "";
    private String workMode = "";
    private String postedDate = "";
    private String error;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getBoardToken() { return boardToken; }
    public void setBoardToken(String boardToken) { this.boardToken = boardToken; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getJobLink() { return jobLink; }
    public void setJobLink(String jobLink) { this.jobLink = jobLink; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getWorkMode() { return workMode; }
    public void setWorkMode(String workMode) { this.workMode = workMode; }
    public String getPostedDate() { return postedDate; }
    public void setPostedDate(String postedDate) { this.postedDate = postedDate; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
