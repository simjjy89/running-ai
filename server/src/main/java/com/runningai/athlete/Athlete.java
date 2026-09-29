package com.runningai.athlete;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.ZoneId;

/**
 * A RunningAI user. The service is single-user today, but every activity is
 * owned by an athlete so that multi-user support does not require a migration.
 */
@Entity
@Table(name = "athlete", uniqueConstraints = {
        @UniqueConstraint(name = "uk_athlete_name", columnNames = "name")
})
@EntityListeners(AuditingEntityListener.class)
public class Athlete {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 64)
    private String timezone;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    protected Athlete() {
        // for JPA
    }

    public Athlete(String name, String timezone) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Athlete name must not be blank");
        }
        this.name = name;
        this.timezone = ZoneId.of(timezone).getId();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getTimezone() {
        return timezone;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
