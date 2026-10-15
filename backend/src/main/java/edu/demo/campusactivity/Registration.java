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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "registrations", uniqueConstraints = @UniqueConstraint(columnNames = {"activity_id", "student_id"}))
public class Registration {
    public enum Status { ACTIVE, CANCELLED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id", nullable = false)
    public Activity activity;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    public UserAccount student;
    @Column(name = "identity_id")
    public Long identityId;
    @Column(name = "binding_id")
    public Long bindingId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Status status = Status.ACTIVE;
    @Column(nullable = false)
    public Instant registeredAt = Instant.now();
    public Instant cancelledAt;

    protected Registration() {}

    public Registration(Activity activity, UserAccount student) {
        this.activity = activity;
        this.student = student;
    }

    public Long getId() { return id; }
}
