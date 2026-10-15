package edu.demo.campusactivity;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

record AuditPage<T>(List<T> items, long total, int page, int size) {
    static <T> AuditPage<T> of(Page<T> source) {
        return new AuditPage<>(source.getContent(), source.getTotalElements(),
            source.getNumber(), source.getSize());
    }
}

@Service
class AdminAuditSearch {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final ActivityAuditRepository activities;
    private final UserSecurityAuditRepository users;
    private final CurrentUser current;

    AdminAuditSearch(ActivityAuditRepository activities, UserSecurityAuditRepository users,
                     CurrentUser current) {
        this.activities = activities;
        this.users = users;
        this.current = current;
    }

    @Transactional(readOnly = true)
    AuditPage<AdminAuditOverview> activity(int page, int size, Long activityId, String actor,
                                           ActivityAudit.Action action, LocalDate from, LocalDate to) {
        current.require(UserAccount.Role.ADMIN);
        validate(page, size, from, to);
        Specification<ActivityAudit> spec = (root, query, cb) -> {
            List<Predicate> terms = new ArrayList<>();
            if (activityId != null) terms.add(cb.equal(root.get("activity").get("id"), activityId));
            if (actor != null && !actor.isBlank()) terms.add(cb.like(cb.lower(root.get("actor").get("name")),
                "%" + actor.trim().toLowerCase(java.util.Locale.ROOT) + "%"));
            if (action != null) terms.add(cb.equal(root.get("action"), action));
            if (from != null) terms.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                from.atStartOfDay(ZONE).toInstant()));
            if (to != null) terms.add(cb.lessThan(root.get("createdAt"),
                to.plusDays(1).atStartOfDay(ZONE).toInstant()));
            return cb.and(terms.toArray(Predicate[]::new));
        };
        return AuditPage.of(activities.findAll(spec, pageRequest(page, size)).map(AdminAuditOverview::of));
    }

    @Transactional(readOnly = true)
    AuditPage<UserSecurityAuditView> user(int page, int size, String actor,
                                          UserSecurityAudit.Action action, LocalDate from, LocalDate to) {
        current.require(UserAccount.Role.ADMIN);
        validate(page, size, from, to);
        Specification<UserSecurityAudit> spec = (root, query, cb) -> {
            List<Predicate> terms = new ArrayList<>();
            if (actor != null && !actor.isBlank()) terms.add(cb.like(cb.lower(root.get("actor").get("name")),
                "%" + actor.trim().toLowerCase(java.util.Locale.ROOT) + "%"));
            if (action != null) terms.add(cb.equal(root.get("action"), action));
            if (from != null) terms.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                from.atStartOfDay(ZONE).toInstant()));
            if (to != null) terms.add(cb.lessThan(root.get("createdAt"),
                to.plusDays(1).atStartOfDay(ZONE).toInstant()));
            return cb.and(terms.toArray(Predicate[]::new));
        };
        return AuditPage.of(users.findAll(spec, pageRequest(page, size)).map(UserSecurityAuditView::of));
    }

    private PageRequest pageRequest(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    }

    private void validate(int page, int size, LocalDate from, LocalDate to) {
        if (page < 0 || page > 10000 || size < 1 || size > 100 || (from != null && to != null && to.isBefore(from))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "审计筛选条件无效");
        }
    }
}

@RestController
@RequestMapping("/api/admin")
class AdminAuditSearchController {
    private final AdminAuditSearch audits;

    AdminAuditSearchController(AdminAuditSearch audits) { this.audits = audits; }

    @GetMapping("/activity-audits/search")
    AuditPage<AdminAuditOverview> activities(
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) Long activityId, @RequestParam(required = false) String actor,
        @RequestParam(required = false) ActivityAudit.Action action,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return audits.activity(page, size, activityId, actor, action, from, to);
    }

    @GetMapping("/user-audits/search")
    AuditPage<UserSecurityAuditView> users(
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String actor,
        @RequestParam(required = false) UserSecurityAudit.Action action,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return audits.user(page, size, actor, action, from, to);
    }
}
