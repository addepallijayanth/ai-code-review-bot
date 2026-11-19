package com.example.reviewbot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A finding as posted on a pull request. {@code ordinal} is the number shown in the review summary
 * (used by {@code @reviewbot explain 3}); {@code accepted} turns true when a later commit contains
 * the suggested code.
 */
@Entity
@Table(name = "finding")
public class Finding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, length = 1024)
    private String file;

    @Column(nullable = false)
    private int line;

    @Column(name = "start_line")
    private Integer startLine;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Category category;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(columnDefinition = "text")
    private String suggestion;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "posted_inline", nullable = false)
    private boolean postedInline;

    @Column(nullable = false)
    private boolean accepted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Finding() {
    }

    public Finding(Long reviewId, int ordinal, String file, int line, Integer startLine, Severity severity,
                   Category category, String title, String message, String suggestion, String fingerprint,
                   boolean postedInline, Instant createdAt) {
        this.reviewId = reviewId;
        this.ordinal = ordinal;
        this.file = file;
        this.line = line;
        this.startLine = startLine;
        this.severity = severity;
        this.category = category;
        this.title = title;
        this.message = message;
        this.suggestion = suggestion;
        this.fingerprint = fingerprint;
        this.postedInline = postedInline;
        this.createdAt = createdAt;
    }

    public void markAccepted() {
        this.accepted = true;
    }

    public Long getId() {
        return id;
    }

    public Long getReviewId() {
        return reviewId;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public String getFile() {
        return file;
    }

    public int getLine() {
        return line;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Category getCategory() {
        return category;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public boolean isPostedInline() {
        return postedInline;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
