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
@Table(name = "checkins", uniqueConstraints = @UniqueConstraint(columnNames = {"activity_id", "student_id"}))
public class Checkin {
    public enum Method { QR, MANUAL }

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
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_id")
    public UserAccount operator;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Method method;
    @Column(nullable = false)
    public Instant checkedAt = Instant.now();
    @Column(length = 500)
    public String reason;

    protected Checkin() {}

    public Checkin(Activity activity, UserAccount student, UserAccount operator, Method method, String reason) {
        this.activity = activity;
        this.student = student;
        this.operator = operator;
        this.method = method;
        this.reason = reason;
    }

    public Long getId() { return id; }
}
