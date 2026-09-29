package com.runningai.activity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;

/**
 * The untouched payload received from an external system for one activity.
 * <p>
 * Exactly one row exists per {@code externalSource + externalId}; a re-fetch
 * replaces {@code payload} and {@code fetchedAt}. The link to the normalised
 * {@link Activity} is optional so a payload can be kept even when normalisation
 * has not happened (or failed) yet.
 */
@Entity
@Table(name = "activity_raw", uniqueConstraints = {
        @UniqueConstraint(name = "uk_activity_raw_external", columnNames = {"external_source", "external_id"})
})
@EntityListeners(AuditingEntityListener.class)
public class ActivityRaw {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id")
    private Activity activity;

    @Enumerated(EnumType.STRING)
    @Column(name = "external_source", nullable = false, length = 32)
    private ExternalSource externalSource;

    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private JsonNode payload;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ActivityRaw() {
        // for JPA
    }

    public ActivityRaw(ExternalSource externalSource, String externalId, JsonNode payload, Instant fetchedAt) {
        this.externalSource = Objects.requireNonNull(externalSource, "externalSource");
        this.externalId = Objects.requireNonNull(externalId, "externalId");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt");
    }

    /** Replaces the stored snapshot with a newer fetch of the same external activity. */
    public void refresh(JsonNode payload, Instant fetchedAt) {
        this.payload = Objects.requireNonNull(payload, "payload");
        this.fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt");
    }

    public void linkTo(Activity activity) {
        this.activity = Objects.requireNonNull(activity, "activity");
    }

    public Long getId() {
        return id;
    }

    public Activity getActivity() {
        return activity;
    }

    public ExternalSource getExternalSource() {
        return externalSource;
    }

    public String getExternalId() {
        return externalId;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
