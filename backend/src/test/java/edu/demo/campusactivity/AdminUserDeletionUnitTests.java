package edu.demo.campusactivity;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminUserDeletionUnitTests {
    @Test
    void nonAdminCannotPreviewOrDelete() {
        var f = new Fixture();
        when(f.current.require(UserAccount.Role.ADMIN)).thenThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "没有操作权限"));
        assertThrows(ApiException.class, () -> f.service.preview(7L));
        assertThrows(ApiException.class, () -> f.service.delete(7L, 7L));
        verifyNoInteractions(f.jdbc, f.users);
    }
    @Test
    void administratorAndSelfAreProtected() {
        var f = new Fixture(); f.target.roles.add(UserAccount.Role.ADMIN);
        assertThrows(ApiException.class, () -> f.service.delete(7L, 7L));
        verifyNoInteractions(f.jdbc);
        verify(f.users, never()).saveAndFlush(any());
    }
    @Test
    void confirmationMustMatchTheAccountId() {
        var f = new Fixture();
        assertThrows(ApiException.class, () -> f.service.delete(7L, 8L));
        verifyNoInteractions(f.jdbc);
        verify(f.users, never()).saveAndFlush(any());
    }
    @Test
    void credentialsAreRemovedBeforeAccountAndAuditKeepsAnonymousReference() {
        var f = new Fixture();
        f.service.delete(7L, 7L);
        var order = inOrder(f.jdbc);
        order.verify(f.jdbc).update("delete from miniapp_access_tokens where binding_id in (select id from wechat_student_bindings where student_id = ?)", 7L);
        order.verify(f.jdbc).update("delete from wechat_student_bindings where student_id = ?", 7L);
        order.verify(f.jdbc).update("delete from user_messages where recipient_id = ?", 7L);
        order.verify(f.jdbc).update("delete from user_roles where user_id = ?", 7L);
        order.verify(f.jdbc).update("delete from user_accounts where id = ?", 7L);
        var saved = org.mockito.ArgumentCaptor.forClass(UserAccount.class);
        verify(f.users).saveAndFlush(saved.capture());
        assertTrue(saved.getValue().roles.isEmpty());
        assertNull(saved.getValue().studentNumber);
        assertNull(saved.getValue().passwordHash);
        assertEquals("已删除用户 #7", saved.getValue().name);
        verify(f.audits).save(argThat(audit -> audit.action == UserSecurityAudit.Action.USER_DELETED));
    }
    private static class Fixture {
        final CurrentUser current = mock(CurrentUser.class);
        final UserRepository users = mock(UserRepository.class);
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final EntityManager entities = mock(EntityManager.class);
        final UserSecurityAuditRepository audits = mock(UserSecurityAuditRepository.class);
        final UserAccount target = new UserAccount("目标学生", null, null, UserAccount.Role.STUDENT);
        final AdminUserDeletionService service;
        Fixture() {
            var actor = new UserAccount("管理员", null, null, UserAccount.Role.ADMIN); actor.id = 1L;
            target.id = 7L;
            when(current.require(UserAccount.Role.ADMIN)).thenReturn(actor);
            when(users.findById(7L)).thenReturn(Optional.of(target));
            when(users.lockUserById(7L)).thenReturn(Optional.of(target));
            when(users.saveAndFlush(any())).thenAnswer(invocation -> { UserAccount archive = invocation.getArgument(0); archive.id = 99L; return archive; });
            when(jdbc.update("delete from user_accounts where id = ?", 7L)).thenReturn(1);
            when(entities.getReference(UserAccount.class, 1L)).thenReturn(actor);
            service = new AdminUserDeletionService(current, users, jdbc, entities, audits, mock(AvatarImageStore.class));
        }
    }
}
