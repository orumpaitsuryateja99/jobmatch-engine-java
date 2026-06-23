package com.orumpati.jobmatch.tracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQL-backed application tracker. Uses a local SQLite database at
 * ./data/jobmatch.db so saved/applied jobs survive restarts and can be used to
 * suppress already-handled jobs in Match & Score. */
@Service
public class TrackerService {

    public static final List<String> STATUSES = List.of(
            "Saved", "Applied", "Phone Screen", "Interview", "Offer", "Rejected", "Withdrawn");

    private final Path dbPath = Path.of("data", "jobmatch.db");
    private final Path legacyStore = Path.of("data", "applications.json");
    private final Path resumeDir = Path.of("data", "resumes");
    private final ObjectMapper mapper = new ObjectMapper();
    private final TrackerEventPublisher events;

    public TrackerService(TrackerEventPublisher events) {
        this.events = events;
        initDb();
        migrateLegacyJson();
    }

    private Connection connect() throws SQLException {
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create data directory", e);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    private void initDb() {
        String ddl = """
                CREATE TABLE IF NOT EXISTS applications (
                  id TEXT PRIMARY KEY,
                  company TEXT NOT NULL DEFAULT '',
                  title TEXT NOT NULL DEFAULT '',
                  job_link TEXT NOT NULL DEFAULT '',
                  source TEXT NOT NULL DEFAULT '',
                  status TEXT NOT NULL DEFAULT 'Applied',
                  applied_date TEXT NOT NULL DEFAULT '',
                  notes TEXT NOT NULL DEFAULT '',
                  has_resume INTEGER NOT NULL DEFAULT 0,
                  resume_name TEXT NOT NULL DEFAULT '',
                  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate(ddl);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_applications_status ON applications(status)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_applications_link ON applications(job_link)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite tracker database", e);
        }
    }

    private void migrateLegacyJson() {
        if (!Files.exists(legacyStore) || countRows() > 0) return;
        try {
            Application[] arr = mapper.readValue(Files.readAllBytes(legacyStore), Application[].class);
            for (Application a : arr) add(a);
        } catch (Exception ignored) {
            // Empty/corrupt legacy JSON should not block the SQL tracker.
        }
    }

    private long countRows() {
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM applications")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count tracker rows", e);
        }
    }

    public synchronized List<Application> all() {
        List<Application> out = new ArrayList<>();
        String sql = "SELECT * FROM applications ORDER BY datetime(created_at) DESC, rowid DESC";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(readApp(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read tracker rows", e);
        }
        return out;
    }

    public synchronized Application add(Application a) {
        String requestedStatus = blank(a.getStatus()) ? "Applied" : a.getStatus();
        try (Connection c = connect()) {
            Application existing = findExisting(c, a);
            if (existing != null) {
                if (!"Saved".equals(requestedStatus) || blank(existing.getStatus())) {
                    if ("Saved".equals(existing.getStatus()) && !"Saved".equals(requestedStatus)) {
                        existing.setAppliedDate(LocalDate.now().toString());
                    }
                    existing.setStatus(requestedStatus);
                }
                if (blank(existing.getCompany())) existing.setCompany(a.getCompany());
                if (blank(existing.getTitle())) existing.setTitle(a.getTitle());
                if (blank(existing.getJobLink())) existing.setJobLink(a.getJobLink());
                if (blank(existing.getSource())) existing.setSource(a.getSource());
                if (blank(existing.getNotes()) && a.getNotes() != null) existing.setNotes(a.getNotes());
                updateApplication(c, existing);
                events.publish("application.updated", existing);
                return existing;
            }

            if (blank(a.getId())) a.setId(UUID.randomUUID().toString().substring(0, 8));
            if (blank(a.getAppliedDate())) a.setAppliedDate(LocalDate.now().toString());
            if (blank(a.getStatus())) a.setStatus("Applied");
            insertApplication(c, a);
            events.publish("application.created", a);
            return a;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save tracker row", e);
        }
    }

    public synchronized boolean updateStatus(String id, String status) {
        try (Connection c = connect()) {
            Application a = findById(c, id);
            if (a == null) return false;
            if ("Saved".equals(a.getStatus()) && !"Saved".equals(status)) {
                a.setAppliedDate(LocalDate.now().toString());
            }
            a.setStatus(status);
            updateApplication(c, a);
            events.publish("application.status_updated", a);
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update tracker status", e);
        }
    }

    public synchronized boolean delete(String id) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("DELETE FROM applications WHERE id = ?")) {
            ps.setString(1, id);
            boolean removed = ps.executeUpdate() > 0;
            if (removed) {
                try { Files.deleteIfExists(resumeDir.resolve(id + ".pdf")); } catch (IOException ignored) {}
                Application tombstone = new Application();
                tombstone.setId(id);
                events.publish("application.deleted", tombstone);
            }
            return removed;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete tracker row", e);
        }
    }

    public synchronized long appliedToday() {
        String sql = "SELECT COUNT(*) FROM applications WHERE applied_date = ? AND status <> 'Saved'";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, LocalDate.now().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count today's applications", e);
        }
    }

    /** Save the exact resume PDF used for an application, for interview prep later. */
    public synchronized boolean saveResume(String id, byte[] bytes, String filename) throws IOException {
        try (Connection c = connect()) {
            Application app = findById(c, id);
            if (app == null) return false;
            Files.createDirectories(resumeDir);
            Files.write(resumeDir.resolve(id + ".pdf"), bytes);
            app.setHasResume(true);
            app.setResumeName(blank(filename) ? "resume.pdf" : filename);
            updateApplication(c, app);
            events.publish("application.resume_saved", app);
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update tracker resume metadata", e);
        }
    }

    public synchronized byte[] getResume(String id) throws IOException {
        Path p = resumeDir.resolve(id + ".pdf");
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    private void insertApplication(Connection c, Application a) throws SQLException {
        String sql = """
                INSERT INTO applications
                (id, company, title, job_link, source, status, applied_date, notes, has_resume, resume_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bindApplication(ps, a);
            ps.executeUpdate();
        }
    }

    private void updateApplication(Connection c, Application a) throws SQLException {
        String sql = """
                UPDATE applications SET
                  company = ?, title = ?, job_link = ?, source = ?, status = ?,
                  applied_date = ?, notes = ?, has_resume = ?, resume_name = ?,
                  updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, safe(a.getCompany()));
            ps.setString(2, safe(a.getTitle()));
            ps.setString(3, safe(a.getJobLink()));
            ps.setString(4, safe(a.getSource()));
            ps.setString(5, blank(a.getStatus()) ? "Applied" : a.getStatus());
            ps.setString(6, safe(a.getAppliedDate()));
            ps.setString(7, safe(a.getNotes()));
            ps.setInt(8, a.isHasResume() ? 1 : 0);
            ps.setString(9, safe(a.getResumeName()));
            ps.setString(10, a.getId());
            ps.executeUpdate();
        }
    }

    private void bindApplication(PreparedStatement ps, Application a) throws SQLException {
        ps.setString(1, safe(a.getId()));
        ps.setString(2, safe(a.getCompany()));
        ps.setString(3, safe(a.getTitle()));
        ps.setString(4, safe(a.getJobLink()));
        ps.setString(5, safe(a.getSource()));
        ps.setString(6, blank(a.getStatus()) ? "Applied" : a.getStatus());
        ps.setString(7, safe(a.getAppliedDate()));
        ps.setString(8, safe(a.getNotes()));
        ps.setInt(9, a.isHasResume() ? 1 : 0);
        ps.setString(10, safe(a.getResumeName()));
    }

    private Application findById(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM applications WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readApp(rs) : null;
            }
        }
    }

    private Application findExisting(Connection c, Application a) throws SQLException {
        String link = safe(a.getJobLink()).strip();
        if (!link.isBlank()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM applications WHERE lower(job_link) = lower(?) LIMIT 1")) {
                ps.setString(1, link);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return readApp(rs);
                }
            }
        }
        String company = safe(a.getCompany()).strip();
        String title = safe(a.getTitle()).strip();
        if (company.isBlank() || title.isBlank()) return null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM applications WHERE lower(company) = lower(?) AND lower(title) = lower(?) LIMIT 1")) {
            ps.setString(1, company);
            ps.setString(2, title);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readApp(rs) : null;
            }
        }
    }

    private Application readApp(ResultSet rs) throws SQLException {
        Application a = new Application();
        a.setId(rs.getString("id"));
        a.setCompany(rs.getString("company"));
        a.setTitle(rs.getString("title"));
        a.setJobLink(rs.getString("job_link"));
        a.setSource(rs.getString("source"));
        a.setStatus(rs.getString("status"));
        a.setAppliedDate(rs.getString("applied_date"));
        a.setNotes(rs.getString("notes"));
        a.setHasResume(rs.getInt("has_resume") == 1);
        a.setResumeName(rs.getString("resume_name"));
        return a;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
