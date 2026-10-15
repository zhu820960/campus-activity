package edu.demo.campusactivity;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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

@Service
class AdminParticipationSearch {
    enum Attendance { CHECKED, NOT_CHECKED }
    private record ParticipantKey(Long activityId, Long studentId) {}

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final RegistrationRepository registrations;
    private final CheckinRepository checkins;
    private final CurrentUser current;

    AdminParticipationSearch(RegistrationRepository registrations, CheckinRepository checkins, CurrentUser current) {
        this.registrations = registrations;
        this.checkins = checkins;
        this.current = current;
    }

    @Transactional(readOnly = true)
    AuditPage<RegistrationView> search(int page, int size, String keyword, Registration.Status status,
                                       Attendance attendance, LocalDate from, LocalDate to) {
        current.require(UserAccount.Role.ADMIN);
        if (page < 0 || page > 10000 || size < 1 || size > 100
            || (keyword != null && keyword.length() > 80)
            || (from != null && to != null && to.isBefore(from))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "参与记录筛选条件无效");
        }
        String term = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String pattern = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Specification<Registration> spec = (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (!term.isEmpty()) {
                conditions.add(cb.or(
                    cb.like(cb.lower(root.get("activity").get("title")), pattern, '\\'),
                    cb.like(cb.lower(root.get("student").get("name")), pattern, '\\'),
                    cb.like(root.get("student").get("studentNumber"), pattern, '\\'),
                    cb.like(cb.lower(root.get("student").get("email")), pattern, '\\')));
            }
            if (status != null) conditions.add(cb.equal(root.get("status"), status));
            if (from != null) conditions.add(cb.greaterThanOrEqualTo(root.get("registeredAt"),
                from.atStartOfDay(ZONE).toInstant()));
            if (to != null) conditions.add(cb.lessThan(root.get("registeredAt"),
                to.plusDays(1).atStartOfDay(ZONE).toInstant()));
            if (attendance != null) {
                Subquery<Long> checked = query.subquery(Long.class);
                Root<Checkin> checkin = checked.from(Checkin.class);
                checked.select(checkin.get("id")).where(
                    cb.equal(checkin.get("activity").get("id"), root.get("activity").get("id")),
                    cb.equal(checkin.get("student").get("id"), root.get("student").get("id")));
                Predicate exists = cb.exists(checked);
                conditions.add(attendance == Attendance.CHECKED ? exists : cb.not(exists));
            }
            return cb.and(conditions.toArray(Predicate[]::new));
        };
        var matches = registrations.findAll(spec,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "registeredAt", "id")));
        Map<ParticipantKey, Checkin> pageCheckins = matches.isEmpty() ? Map.of()
            : checkins.findForRegistrations(matches.getContent().stream().map(r -> r.id).toList())
                .stream().collect(Collectors.toMap(
                    c -> new ParticipantKey(c.activity.getId(), c.student.getId()), Function.identity()));
        var result = matches.map(registration -> RegistrationView.forManagement(registration,
            pageCheckins.get(new ParticipantKey(registration.activity.getId(), registration.student.getId()))));
        return AuditPage.of(result);
    }
}

@RestController
@RequestMapping("/api/admin/participation")
class AdminParticipationSearchController {
    private final AdminParticipationSearch search;

    AdminParticipationSearchController(AdminParticipationSearch search) { this.search = search; }

    @GetMapping
    AuditPage<RegistrationView> list(
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) Registration.Status status,
        @RequestParam(required = false) AdminParticipationSearch.Attendance attendance,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return search.search(page, size, keyword, status, attendance, from, to);
    }
}
