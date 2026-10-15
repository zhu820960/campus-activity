package edu.demo.campusactivity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

@Entity
@Table(name = "activity_feedback", indexes = {
    @Index(name = "idx_feedback_activity_created", columnList = "activity_id,created_at")
}, uniqueConstraints = @UniqueConstraint(name = "uk_feedback_activity_student",
    columnNames = {"activity_id", "student_id"}))
class ActivityFeedback {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id", nullable = false)
    Activity activity;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    UserAccount student;
    @Column(nullable = false)
    int rating;
    @Column(nullable = false, length = 1000)
    String comment;
    @Column(name = "created_at", nullable = false)
    Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt = Instant.now();
    @Column(name = "hidden_at")
    Instant hiddenAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "moderator_id")
    UserAccount moderator;
    @Column(name = "hidden_reason", length = 300)
    String hiddenReason;

    protected ActivityFeedback() {}

    ActivityFeedback(Activity activity, UserAccount student, int rating, String comment) {
        this.activity = activity;
        this.student = student;
        this.rating = rating;
        this.comment = comment;
    }
}

interface ActivityFeedbackRepository extends JpaRepository<ActivityFeedback, Long> {
    Optional<ActivityFeedback> findByActivityIdAndStudentId(Long activityId, Long studentId);
    List<ActivityFeedback> findByActivityIdOrderByCreatedAtDesc(Long activityId);
}
