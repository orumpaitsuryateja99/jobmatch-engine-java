package com.orumpati.jobmatch.resume;

import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.List;

/** Default PDFTextStripper joins every TextPosition on the same visual row into one
 * output line, even across a wide \hfill-style gap (e.g. a LaTeX résumé's
 * "Role ... [hfill] ... Date" row becomes ONE line: "Role [Live] [GitHub] Date").
 * PyMuPDF (the reference extractor app/resume_parser.py was tuned against) instead
 * emits each visually-separated run as its OWN line, which is what the rigid
 * positional block-parsers (_parse_experience/_parse_projects/_parse_education)
 * expect.
 *
 * PDFTextStripper calls writeWordSeparator() BEFORE it knows the next run's
 * position, so the separator decision (space vs. line break) has to be deferred
 * until the next writeString() call reveals where that run actually starts. */
public class ColumnAwarePdfStripper extends PDFTextStripper {

    private Float lastEndX;
    private Float lastEndY;
    private boolean pendingSpace;

    public ColumnAwarePdfStripper() throws IOException {
        super();
    }

    @Override
    protected void writeWordSeparator() throws IOException {
        pendingSpace = true;
    }

    @Override
    protected void writeLineSeparator() throws IOException {
        pendingSpace = false;
        lastEndX = null;
        lastEndY = null;
        super.writeLineSeparator();
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
        if (textPositions != null && !textPositions.isEmpty()) {
            TextPosition first = textPositions.get(0);
            if (pendingSpace) {
                boolean sameLine = lastEndY != null && Math.abs(first.getY() - lastEndY) < 2f;
                float gap = lastEndX != null ? first.getX() - lastEndX : 0f;
                boolean bigGap = sameLine && gap > 18f && gap > 4f * Math.max(first.getWidth(), 1f);
                super.writeString(bigGap ? "\n" : " ");
                pendingSpace = false;
            }
            TextPosition last = textPositions.get(textPositions.size() - 1);
            lastEndX = last.getX() + last.getWidth();
            lastEndY = last.getY();
        }
        super.writeString(text);
    }
}
