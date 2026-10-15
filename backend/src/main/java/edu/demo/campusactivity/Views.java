package edu.demo.campusactivity;

import java.time.Instant;

record ActivityView(Long id, String title, String description, String articleSummary,
                    String coverImageUrl, String location, Double longitude, Double latitude,
                    Instant startsAt, Instant endsAt, Instant registrationOpensAt,
                    Instant registrationClosesAt, Instant checkinOpensAt, Instant checkinClosesAt,
                    int capacity, int registeredCount, Activity.Status status,
                    Long organizerId, String organizerName, String reviewNote, Instant publishedAt,
                    String cancelReason) {
    static ActivityView of(Activity a) {
        return new ActivityView(a.id, a.title, a.description, a.articleSummary,
            a.coverImageUrl, a.location, a.longitude, a.latitude, a.startsAt, a.endsAt,
            a.registrationOpensAt, a.registrationClosesAt, a.checkinOpensAt, a.checkinClosesAt,
            a.capacity, a.registeredCount, a.status, a.organizer.getId(), a.organizer.getName(),
            a.reviewNote, a.publishedAt, a.cancelReason);
    }
}

record RegistrationView(Long id, Long activityId, String activityTitle, Long studentId,
                        String studentName, String studentNumber, String studentEmail, Registration.Status status,
                        Instant registeredAt, Instant cancelledAt, Instant checkedAt, Checkin.Method checkinMethod,
                        String checkinOperatorName, String manualCheckinReason) {
    static RegistrationView of(Registration r, Checkin c) {
        return new RegistrationView(r.id, r.activity.getId(), r.activity.getTitle(), r.student.getId(),
            r.student.getName(), r.student.getStudentNumber(), r.student.getEmail(), r.status, r.registeredAt, r.cancelledAt,
            c == null ? null : c.checkedAt, c == null ? null : c.method, null, null);
    }

    static RegistrationView forManagement(Registration r, Checkin c) {
        RegistrationView base = of(r, c);
        return new RegistrationView(base.id(), base.activityId(), base.activityTitle(), base.studentId(),
            base.studentName(), base.studentNumber(), base.studentEmail(), base.status(), base.registeredAt(), base.cancelledAt(),
            base.checkedAt(), base.checkinMethod(),
            c == null || c.operator == null ? null : c.operator.getName(),
            c == null ? null : c.reason);
    }
}

record StatsView(int registeredCount, long checkedCount, long absentCount, int capacity) {
    static StatsView of(Activity a, long checkedCount) {
        return new StatsView(a.registeredCount, checkedCount,
            Math.max(0, a.registeredCount - checkedCount), a.capacity);
    }
}

record AdminActivityOverview(ActivityView activity, StatsView stats) {}

record ActivityAuditView(ActivityAudit.Action action, String actorName, Instant createdAt, String detail) {
    static ActivityAuditView of(ActivityAudit event) {
        return new ActivityAuditView(event.action, event.actor.getName(), event.createdAt, event.detail);
    }
}

record AdminAuditOverview(Long activityId, String activityTitle, ActivityAudit.Action action,
                          String actorName, Instant createdAt, String detail) {
    static AdminAuditOverview of(ActivityAudit event) {
        return new AdminAuditOverview(event.activity.getId(), event.activity.getTitle(),
            event.action, event.actor.getName(), event.createdAt, event.detail);
    }
}
