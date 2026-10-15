package edu.demo.campusactivity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "activities")
public class Activity {
    public enum Status { DRAFT, PENDING, PUBLISHED, REJECTED, CANCELLED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 160)
    public String title;
    @Column(nullable = false, length = 5000)
    public String description;
    @Column(length = 300)
    public String articleSummary;
    @Column(length = 1000)
    public String coverImageUrl;
    @Column(nullable = false, length = 200)
    public String location;
    public Double longitude;
    public Double latitude;
    @Column(nullable = false)
    public Instant startsAt;
    @Column(nullable = false)
    public Instant endsAt;
    @Column(nullable = false)
    public Instant registrationOpensAt;
    @Column(nullable = false)
    public Instant registrationClosesAt;
    @Column(nullable = false)
    public Instant checkinOpensAt;
    @Column(nullable = false)
    public Instant checkinClosesAt;
    @Column(nullable = false)
    public int capacity;
    @Column(nullable = false)
    public int registeredCount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Status status = Status.DRAFT;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizer_id", nullable = false)
    public UserAccount organizer;
    @Column(nullable = false)
    public Instant createdAt = Instant.now();
    public Instant publishedAt;
    public String checkinTokenHash;
    public Instant checkinTokenExpiresAt;
    @Column(length = 500)
    public String reviewNote;
    @Column(name = "cancel_reason", length = 500)
    public String cancelReason;

    protected Activity() {}

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public Status getStatus() { return status; }
}
