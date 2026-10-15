package edu.demo.campusactivity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.criteria.Predicate;
import tools.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Entity
@Table(name = "participation_export_audits")
class ParticipationExportAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    UserAccount actor;
    @Column(nullable = false, length = 20)
    String scope;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id")
    Activity activity;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filters_json", nullable = false, columnDefinition = "json")
    String filtersJson;
    @Column(nullable = false, length = 8)
    String format;
    @Column(name = "row_count", nullable = false)
    int rowCount;
    @Column(nullable = false, length = 20)
    String outcome;
    @Column(name = "created_at", nullable = false)
    Instant createdAt = Instant.now();

    protected ParticipationExportAudit() {}

    ParticipationExportAudit(UserAccount actor, Activity activity, String scope, String list, int rowCount) {
        this(actor, activity, scope, list, "XLSX", rowCount);
    }

    ParticipationExportAudit(UserAccount actor, Activity activity, String scope, String list,
                             String format, int rowCount) {
        this.actor = actor;
        this.scope = scope;
        this.activity = activity;
        this.filtersJson = "{\"list\":\"" + list + "\"}";
        this.format = format;
        this.rowCount = rowCount;
        this.outcome = "GENERATED";
    }
}

interface ParticipationExportAuditRepository extends JpaRepository<ParticipationExportAudit, Long>,
    JpaSpecificationExecutor<ParticipationExportAudit> {}

record ParticipationExportAuditView(Long id, Long activityId, String activityTitle, String actorName,
                                    String scope, String listType, int rowCount, String outcome,
                                    Instant createdAt) {}

@Service
class ParticipationExportAuditSearch {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final ParticipationExportAuditRepository audits;
    private final CurrentUser current;
    private final ObjectMapper mapper;

    ParticipationExportAuditSearch(ParticipationExportAuditRepository audits, CurrentUser current,
                                   ObjectMapper mapper) {
        this.audits = audits;
        this.current = current;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    AuditPage<ParticipationExportAuditView> search(int page, int size, Long activityId, String actor,
                                                     LocalDate from, LocalDate to) {
        current.require(UserAccount.Role.ADMIN);
        if (page < 0 || page > 10000 || size < 1 || size > 100
            || (activityId != null && activityId < 1)
            || (actor != null && actor.length() > 80)
            || (from != null && to != null && to.isBefore(from))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "导出审计筛选条件无效");
        }
        Specification<ParticipationExportAudit> spec = (root, query, cb) -> {
            List<Predicate> terms = new ArrayList<>();
            if (activityId != null) terms.add(cb.equal(root.get("activity").get("id"), activityId));
            if (actor != null && !actor.isBlank()) terms.add(cb.like(cb.lower(root.get("actor").get("name")),
                "%" + actor.trim().toLowerCase(java.util.Locale.ROOT) + "%"));
            if (from != null) terms.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                from.atStartOfDay(ZONE).toInstant()));
            if (to != null) terms.add(cb.lessThan(root.get("createdAt"),
                to.plusDays(1).atStartOfDay(ZONE).toInstant()));
            return cb.and(terms.toArray(Predicate[]::new));
        };
        return AuditPage.of(audits.findAll(spec, PageRequest.of(page, size,
            Sort.by(Sort.Direction.DESC, "id"))).map(audit -> new ParticipationExportAuditView(
                audit.id, audit.activity == null ? null : audit.activity.getId(),
                audit.activity == null ? "" : audit.activity.getTitle(), audit.actor.getName(),
                audit.scope, listType(audit.filtersJson), audit.rowCount, audit.outcome, audit.createdAt)));
    }

    private String listType(String filtersJson) {
        try {
            return mapper.readTree(filtersJson).path("list").asText("");
        } catch (tools.jackson.core.JacksonException exception) {
            return "";
        }
    }
}

@RestController
@RequestMapping("/api/admin/participation-export-audits")
class ParticipationExportAuditController {
    private final ParticipationExportAuditSearch search;

    ParticipationExportAuditController(ParticipationExportAuditSearch search) { this.search = search; }

    @GetMapping
    AuditPage<ParticipationExportAuditView> list(
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) Long activityId, @RequestParam(required = false) String actor,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return search.search(page, size, activityId, actor, from, to);
    }
}

@Service
class ParticipationExportService {
    enum ListType { REGISTRATIONS, CHECKINS }
    private static final int PAGE_SIZE = 500;
    private static final int MAX_ROWS = 100_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.of("Asia/Shanghai"));
    private final ActivityService activities;
    private final RegistrationRepository registrations;
    private final CheckinRepository checkins;
    private final ParticipationExportAuditRepository audits;
    private final CurrentUser current;

    ParticipationExportService(ActivityService activities, RegistrationRepository registrations,
                               CheckinRepository checkins, ParticipationExportAuditRepository audits,
                               CurrentUser current) {
        this.activities = activities;
        this.registrations = registrations;
        this.checkins = checkins;
        this.audits = audits;
        this.current = current;
    }

    @Transactional
    byte[] organizer(Long activityId, ListType type) {
        UserAccount actor = current.require(UserAccount.Role.STUDENT);
        if (!actor.roles.contains(UserAccount.Role.ORGANIZER) || actor.roles.contains(UserAccount.Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "仅学生组织者可导出本人活动名单");
        }
        Activity activity = activities.owned(activityId);
        return generate(actor, activity, type, "ORGANIZER");
    }

    @Transactional
    byte[] admin(Long activityId, ListType type) {
        UserAccount actor = current.require(UserAccount.Role.ADMIN);
        return generate(actor, activities.find(activityId), type, "ADMIN");
    }

    private byte[] generate(UserAccount actor, Activity activity, ListType type, String scope) {
        Long activityId = activity.id;
        Map<Long, Checkin> checked = new HashMap<>();
        if (type == ListType.CHECKINS) {
            for (Checkin checkin : checkins.findByActivityId(activityId)) {
                checked.put(checkin.student.getId(), checkin);
            }
        }
        int count = 0;
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet(type == ListType.CHECKINS ? "签到名单" : "报名名单");
            String[] columns = type == ListType.CHECKINS
                ? new String[] {"活动名称", "活动开始", "活动结束", "活动地点", "学号", "姓名", "签到时间"}
                : new String[] {"活动名称", "活动开始", "活动结束", "活动地点", "学号", "姓名"};
            writeRow(sheet.createRow(0), columns);
            int page = 0;
            org.springframework.data.domain.Page<Registration> batch;
            do {
                batch = registrations.findByActivityIdAndStatus(activityId, Registration.Status.ACTIVE,
                    PageRequest.of(page++, PAGE_SIZE, Sort.by("registeredAt", "id")));
                for (Registration registration : batch.getContent()) {
                    Checkin checkin = checked.get(registration.student.getId());
                    if (type == ListType.CHECKINS && (checkin == null
                        || !sameSource(registration, checkin))) continue;
                    if (++count > MAX_ROWS) {
                        throw new ApiException(HttpStatus.CONFLICT, "名单超过单次导出上限，请联系管理员分批处理");
                    }
                    Row row = sheet.createRow(count);
                    writeRow(row, activity.title, TIME.format(activity.startsAt), TIME.format(activity.endsAt),
                        activity.location, registration.student.studentNumber, registration.student.name);
                    if (type == ListType.CHECKINS) safeCell(row.createCell(6), TIME.format(checkin.checkedAt));
                }
            } while (batch.hasNext());
            int[] widths = {34, 20, 20, 36, 22, 22, 20};
            for (int i = 0; i < columns.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            workbook.write(output);
            audits.saveAndFlush(new ParticipationExportAudit(actor, activity, scope, type.name(), count));
            return output.toByteArray();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "名单生成失败，请稍后重试");
        }
    }

    private static boolean sameSource(Registration registration, Checkin checkin) {
        return java.util.Objects.equals(registration.identityId, checkin.identityId)
            && java.util.Objects.equals(registration.bindingId, checkin.bindingId);
    }

    private static void writeRow(Row row, String... values) {
        for (int i = 0; i < values.length; i++) safeCell(row.createCell(i), values[i]);
    }

    private static void safeCell(Cell cell, String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r\n".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        cell.setCellValue(text);
    }
}

@RestController
@RequestMapping("/api/miniapp/organizer/activities")
class ParticipationExportController {
    private static final MediaType XLSX = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final ParticipationExportService exports;

    ParticipationExportController(ParticipationExportService exports) { this.exports = exports; }

    @GetMapping("/{id}/exports/registrations")
    ResponseEntity<byte[]> registrations(@PathVariable Long id) {
        return file(id, ParticipationExportService.ListType.REGISTRATIONS);
    }

    @GetMapping("/{id}/exports/checkins")
    ResponseEntity<byte[]> checkins(@PathVariable Long id) {
        return file(id, ParticipationExportService.ListType.CHECKINS);
    }

    private ResponseEntity<byte[]> file(Long id, ParticipationExportService.ListType type) {
        byte[] content = exports.organizer(id, type);
        String suffix = type == ParticipationExportService.ListType.CHECKINS ? "checkins" : "registrations";
        return ResponseEntity.ok()
            .contentType(XLSX)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=activity-" + id + "-" + suffix + ".xlsx")
            .body(content);
    }
}

@RestController
@RequestMapping("/api/admin/activities")
class AdminParticipationExportController {
    private static final MediaType XLSX = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final ParticipationExportService exports;

    AdminParticipationExportController(ParticipationExportService exports) { this.exports = exports; }

    @GetMapping("/{id}/exports/registrations")
    ResponseEntity<byte[]> registrations(@PathVariable Long id) {
        return file(id, ParticipationExportService.ListType.REGISTRATIONS);
    }

    @GetMapping("/{id}/exports/checkins")
    ResponseEntity<byte[]> checkins(@PathVariable Long id) {
        return file(id, ParticipationExportService.ListType.CHECKINS);
    }

    private ResponseEntity<byte[]> file(Long id, ParticipationExportService.ListType type) {
        byte[] content = exports.admin(id, type);
        String suffix = type == ParticipationExportService.ListType.CHECKINS ? "checkins" : "registrations";
        return ResponseEntity.ok()
            .contentType(XLSX)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=activity-" + id + "-" + suffix + ".xlsx")
            .body(content);
    }
}
