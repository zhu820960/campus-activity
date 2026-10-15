package edu.demo.campusactivity;

import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

/** Deletes the login account, retaining an inert, anonymous reference for historical foreign keys. */
@Service
class AdminUserDeletionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminUserDeletionService.class);
    record Impact(Long id, String name, long activities, long registrations, long checkins, long bindings, long messages) {}
    private final CurrentUser current;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final UserSecurityAuditRepository audits;
    private final AvatarImageStore avatars;

    AdminUserDeletionService(CurrentUser current, UserRepository users, JdbcTemplate jdbc,
                             EntityManager entities, UserSecurityAuditRepository audits, AvatarImageStore avatars) {
        this.current = current; this.users = users; this.jdbc = jdbc; this.entities = entities;
        this.audits = audits; this.avatars = avatars;
    }

    private UserAccount target(Long id, UserAccount actor, boolean lock) {
        UserAccount target = (lock ? users.lockUserById(id) : users.findById(id))
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "用户不存在或已删除"));
        target = org.hibernate.Hibernate.unproxy(target, UserAccount.class);
        if (target.getId().equals(actor.getId()) || target.getRoles().contains(UserAccount.Role.ADMIN)) {
            throw new ApiException(HttpStatus.CONFLICT, "不能删除管理员账号或当前登录账号");
        }
        if (target.getRoles().isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "用户不存在或已删除");
        return target;
    }

    @Transactional(readOnly = true)
    Impact preview(Long id) {
        UserAccount actor = current.require(UserAccount.Role.ADMIN);
        UserAccount target = target(id, actor, false);
        return new Impact(id, target.getName(), count("select count(*) from activities where organizer_id = ?", id),
            count("select count(*) from registrations where student_id = ?", id),
            count("select count(*) from checkins where student_id = ?", id),
            count("select count(*) from wechat_student_bindings where student_id = ?", id),
            count("select count(*) from user_messages where recipient_id = ?", id));
    }

    private long count(String sql, Long id) { return jdbc.queryForObject(sql, Long.class, id); }

    @Transactional
    void delete(Long id, Long confirmedId) {
        UserAccount actor = current.require(UserAccount.Role.ADMIN);
        UserAccount target = target(id, actor, true);
        if (!id.equals(confirmedId)) throw new ApiException(HttpStatus.BAD_REQUEST, "请输入要删除的用户ID以确认");
        String avatarKey = target.getAvatarKey();
        // The archive has no identity fields, credentials or roles and is excluded from account management.
        UserAccount archive = new UserAccount();
        archive.name = "已删除用户 #" + id;
        archive = users.saveAndFlush(archive);
        Long archiveId = archive.getId();
        entities.flush();

        // Lock affected activities in ID order before releasing any future, unchecked reservations.
        jdbc.queryForList("select id from activities where organizer_id = ? or id in "
            + "(select activity_id from registrations where student_id = ?) order by id for update", Long.class, id, id);
        List<Long> futureUnchecked = jdbc.queryForList("select r.id from registrations r join activities a on a.id = r.activity_id "
            + "where r.student_id = ? and r.status = 'ACTIVE' and a.starts_at > UTC_TIMESTAMP(6) "
            + "and not exists (select 1 from checkins c where c.activity_id = r.activity_id and c.student_id = r.student_id)", Long.class, id);
        for (Long registrationId : futureUnchecked) {
            jdbc.update("update registrations set status = 'CANCELLED', cancelled_at = UTC_TIMESTAMP(6) where id = ?", registrationId);
        }
        jdbc.update("update activities set registered_count = (select count(*) from registrations r where r.activity_id = activities.id and r.status = 'ACTIVE') "
            + "where id in (select activity_id from registrations where student_id = ?)", id);
        jdbc.update("update activities set organizer_id = ? where organizer_id = ?", actor.getId(), id);

        // Authorship is anonymized, never reassigned to the deleting administrator.
        for (String reference : List.of("activity_audits:actor_id", "user_security_audits:actor_id", "user_security_audits:target_id",
            "checkins:operator_id", "activity_feedback:moderator_id", "activity_feedback:student_id",
            "activity_change_requests:reviewer_id", "activity_mutation_audits:actor_id", "participation_export_audits:actor_id",
            "participation_attribution_reviews:source_student_id", "participation_attribution_reviews:target_student_id",
            "participation_attribution_reviews:reviewer_id", "binding_appeals:reviewer_id", "wechat_student_bindings:unbound_by")) {
            String[] parts = reference.split(":");
            jdbc.update("update " + parts[0] + " set " + parts[1] + " = ? where " + parts[1] + " = ?", archiveId, id);
        }
        jdbc.update("update activity_change_requests set status = 'REJECTED', reviewer_id = ?, reviewed_at = UTC_TIMESTAMP(6), "
            + "review_note = '申请账号已删除，原申请终止' where requester_id = ? and status = 'PENDING'", actor.getId(), id);
        jdbc.update("update activity_change_requests set requester_id = ? where requester_id = ?", archiveId, id);
        jdbc.update("update registrations set student_id = ?, identity_id = null, binding_id = null where student_id = ?", archiveId, id);
        jdbc.update("update checkins set student_id = ?, identity_id = null, binding_id = null where student_id = ?", archiveId, id);

        // Other accounts may refer to a historical binding after a reviewed transfer: clear only the removed FK.
        for (String table : List.of("registrations", "checkins")) {
            jdbc.update("update " + table + " set binding_id = null where binding_id in "
                + "(select id from wechat_student_bindings where student_id = ?)", id);
        }
        jdbc.update("update binding_appeals set status = 'REJECTED', reviewer_id = ?, reviewed_at = UTC_TIMESTAMP(6), "
            + "review_note = '关联账号已删除，原申请终止' where current_binding_id in "
            + "(select id from wechat_student_bindings where student_id = ?) and status in ('PENDING','DISPUTED')", actor.getId(), id);
        jdbc.update("update binding_appeals set current_binding_id = null where current_binding_id in "
            + "(select id from wechat_student_bindings where student_id = ?)", id);
        jdbc.update("delete from miniapp_access_tokens where binding_id in (select id from wechat_student_bindings where student_id = ?)", id);
        jdbc.update("delete from wechat_student_bindings where student_id = ?", id);
        jdbc.update("delete from user_messages where recipient_id = ?", id);
        jdbc.update("delete from user_roles where user_id = ?", id);
        if (jdbc.update("delete from user_accounts where id = ?", id) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "账号已变化，请重新核对");
        }
        entities.clear();
        audits.save(new UserSecurityAudit(entities.getReference(UserAccount.class, actor.getId()),
            entities.getReference(UserAccount.class, archiveId), UserSecurityAudit.Action.USER_DELETED));
        if (avatarKey != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    try { avatars.deleteQuietly(avatarKey); }
                    catch (RuntimeException cleanupError) { log.warn("删除账号后头像文件清理失败，用户ID={}", id); }
                }
            });
        }
    }
}

@RestController
@RequestMapping("/api/admin/users")
class AdminUserDeletionController {
    record DeleteRequest(@NotNull Long confirmUserId) {}
    private final AdminUserDeletionService service;
    AdminUserDeletionController(AdminUserDeletionService service) { this.service = service; }
    @GetMapping("/{id}/deletion-impact")
    AdminUserDeletionService.Impact preview(@PathVariable Long id) { return service.preview(id); }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable Long id, @Valid @RequestBody DeleteRequest request) { service.delete(id, request.confirmUserId()); }
}
