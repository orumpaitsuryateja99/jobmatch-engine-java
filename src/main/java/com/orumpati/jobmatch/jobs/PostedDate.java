package com.orumpati.jobmatch.jobs;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java port of the posted_datetime / posted_age_hours machinery in app/sources.py.
 * Parses relative phrases ("3 days ago"), absolute dates, ISO strings, and epoch
 * ms/seconds into an Instant so freshness filtering matches the Python behavior. */
public final class PostedDate {

    private static final Pattern AGO_WORD_RE = Pattern.compile(
            "\\b(just posted|posted just now|just now|today)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern YESTERDAY_RE = Pattern.compile("\\byesterday\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AGO_RE = Pattern.compile(
            "\\b(?:posted\\s+)?(\\d+)\\s*(minute|minutes|min|mins|hour|hours|hr|hrs|day|days|week|weeks|month|months)\\s+ago\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AGO_SHORT_RE = Pattern.compile(
            "\\b(\\d+)\\s*([mhdw])\\s+ago\\b", Pattern.CASE_INSENSITIVE);

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("january", 1), Map.entry("feb", 2), Map.entry("february", 2),
            Map.entry("mar", 3), Map.entry("march", 3), Map.entry("apr", 4), Map.entry("april", 4),
            Map.entry("may", 5), Map.entry("jun", 6), Map.entry("june", 6), Map.entry("jul", 7), Map.entry("july", 7),
            Map.entry("aug", 8), Map.entry("august", 8), Map.entry("sep", 9), Map.entry("sept", 9), Map.entry("september", 9),
            Map.entry("oct", 10), Map.entry("october", 10), Map.entry("nov", 11), Map.entry("november", 11),
            Map.entry("dec", 12), Map.entry("december", 12));

    public static Instant relative(String text, Instant now) {
        if (text == null || text.isEmpty()) return null;
        String t = text.toLowerCase();
        if (AGO_WORD_RE.matcher(t).find()) return now;
        if (YESTERDAY_RE.matcher(t).find()) return now.minus(java.time.Duration.ofDays(1));
        Matcher m = AGO_RE.matcher(t);
        if (!m.find()) {
            m = AGO_SHORT_RE.matcher(t);
            if (!m.find()) return null;
        }
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2);
        return switch (unit) {
            case "minute", "minutes", "min", "mins", "m" -> now.minusSeconds(n * 60);
            case "hour", "hours", "hr", "hrs", "h" -> now.minusSeconds(n * 3600);
            case "day", "days", "d" -> now.minusSeconds(n * 86400);
            case "week", "weeks", "w" -> now.minusSeconds(n * 604800);
            case "month", "months" -> now.minusSeconds(n * 30 * 86400);
            default -> null;
        };
    }

    private static final Pattern ISO_DATE_RE = Pattern.compile("\\b(20\\d{2})-(\\d{1,2})-(\\d{1,2})\\b");
    private static final Pattern ABS_DATE_RE = Pattern.compile(
            "\\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|"
                    + "jul(?:y)?|aug(?:ust)?|sep(?:t|tember)?|oct(?:ober)?|nov(?:ember)?|"
                    + "dec(?:ember)?)\\s+(\\d{1,2}),?\\s+(20\\d{2})\\b", Pattern.CASE_INSENSITIVE);

    public static Instant absolute(String text, Instant now) {
        if (text == null || text.isEmpty()) return null;
        String s = text.strip();
        Matcher m = ISO_DATE_RE.matcher(s);
        if (m.find()) {
            try {
                return OffsetDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
            } catch (Exception ignored) {}
        }
        m = ABS_DATE_RE.matcher(s);
        if (m.find()) {
            Integer mon = MONTHS.get(m.group(1).toLowerCase().substring(0, Math.min(3, m.group(1).length())));
            if (mon == null) return null;
            try {
                return OffsetDateTime.of(Integer.parseInt(m.group(3)), mon, Integer.parseInt(m.group(2)),
                        0, 0, 0, 0, ZoneOffset.UTC).toInstant();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** ts may be an ISO/epoch/relative-phrase string, or null. */
    public static Instant parse(Object ts, Instant now) {
        if (now == null) now = Instant.now();
        if (ts == null || "".equals(ts)) return null;
        if (ts instanceof Instant inst) return inst;
        Instant rel = relative(String.valueOf(ts), now);
        if (rel != null) return rel;
        if (ts instanceof Number num) {
            double v = num.doubleValue();
            double sec = v > 1e12 ? v / 1000.0 : v;
            return Instant.ofEpochMilli((long) (sec * 1000));
        }
        String s = String.valueOf(ts).strip().replace("Z", "+00:00");
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            return absolute(s, now);
        }
    }

    public static Double postedAgeHours(Object ts) {
        if (ts == null || "".equals(ts)) return null;
        Instant dt = parse(ts, Instant.now());
        if (dt == null) return null;
        return (Instant.now().getEpochSecond() - dt.getEpochSecond()) / 3600.0;
    }

    public static String postedDateStr(Object ts) {
        Instant dt = parse(ts, Instant.now());
        return dt == null ? "" : dt.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    public static boolean tooOld(Object ts, Double maxAgeHours) {
        if (maxAgeHours == null) return false;
        Double age = postedAgeHours(ts);
        if (age == null) return true;
        return age > maxAgeHours;
    }

    private PostedDate() {}
}
