package edu.demo.campusactivity;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebAccountNotificationTests {
    @Test
    void organizerNotificationsRequireBothAccountAndCurrentWechatIdentity() {
        var f = new Fixture(UserAccount.Role.ORGANIZER);
        when(f.bindings.findActiveIdentityId("app", 1L)).thenReturn(Optional.of(7L));
        when(f.repo.findTop100ByRecipientIdAndIdentityIdOrderByCreatedAtDesc(1L, 7L))
            .thenReturn(List.of(f.message(3L, 7L, f.activity, "review:3")));
        assertEquals("/organizer/10", f.service.webMine().get(0).targetPath());
        verify(f.repo).findTop100ByRecipientIdAndIdentityIdOrderByCreatedAtDesc(1L, 7L);
        assertThrows(ApiException.class, () -> f.service.webRead(99L));
        verify(f.repo).findByIdAndRecipientIdAndIdentityId(99L, 1L, 7L);
    }

    @Test
    void adminReadsAccountNotificationsWithoutRequiringWechatAndAppealsHaveDeepLinks() {
        var f = new Fixture(UserAccount.Role.ADMIN);
        when(f.repo.findTop100ByRecipientIdAndIdentityIdOrderByCreatedAtDesc(1L, null))
            .thenReturn(List.of(f.message(3L, null, null, "admin:1:appeal:25:APPROVED")));
        assertEquals("/admin/users?appeal=25", f.service.webMine().get(0).targetPath());
        verifyNoInteractions(f.bindings);
    }

    @Test
    void markingReadDoesNotMutateActivityStatusAndRepeatedReadKeepsTimestamp() {
        var f = new Fixture(UserAccount.Role.ADMIN);
        var message = f.message(3L, null, f.activity, "admin:1:review:3");
        when(f.repo.findByIdAndRecipientIdAndIdentityId(3L, 1L, null)).thenReturn(Optional.of(message));
        var first = f.service.webRead(3L);
        assertNotNull(first.readAt());
        assertEquals(first.readAt(), f.service.webRead(3L).readAt());
        assertEquals(Activity.Status.DRAFT, f.activity.status);
        assertEquals("/admin/activities/10", first.targetPath());
    }

    @Test
    void notificationsNeverOpenAnActivityOwnedByAnotherOrganizer() {
        var f = new Fixture(UserAccount.Role.ORGANIZER);
        var other = new UserAccount("他人", null, null, UserAccount.Role.ORGANIZER); other.id = 2L;
        f.activity.organizer = other;
        when(f.repo.findTop100ByRecipientIdAndIdentityIdOrderByCreatedAtDesc(1L, null))
            .thenReturn(List.of(f.message(3L, null, f.activity, "web-account:review:3")));
        assertEquals("/account?notification=3", f.service.webMine().get(0).targetPath());
    }

    @Test
    void unboundOrganizerCannotSeeUnknownHistoricalMessages() {
        var f = new Fixture(UserAccount.Role.ORGANIZER);
        var unknown = f.message(3L, null, null, "legacy:3");
        when(f.repo.findTop100ByRecipientIdAndIdentityIdOrderByCreatedAtDesc(1L, null)).thenReturn(List.of(unknown));
        when(f.repo.findByIdAndRecipientIdAndIdentityId(3L, 1L, null)).thenReturn(Optional.of(unknown));
        assertTrue(f.service.webMine().isEmpty());
        assertThrows(ApiException.class, () -> f.service.webRead(3L));
        assertNull(unknown.readAt);
    }

    @Test
    void webAvatarUsesSamePrivateStoreAndNeverAcceptsAnotherAccountId() {
        var current = mock(CurrentUser.class);
        var store = mock(AvatarImageStore.class);
        var account = new UserAccount("管理员", null, null, UserAccount.Role.ADMIN);
        account.avatarKey = "a".repeat(32) + ".png";
        when(current.require(UserAccount.Role.ADMIN, UserAccount.Role.ORGANIZER)).thenReturn(account);
        when(store.read(account.avatarKey)).thenReturn(new org.springframework.core.io.ByteArrayResource(new byte[] {1}));
        var service = new StudentAvatarService(current, mock(UserRepository.class), store);
        assertEquals("private, no-store", service.read().getHeaders().getFirst("Cache-Control"));
        verify(store).read(account.avatarKey);
    }

    private static class Fixture {
        final UserMessageRepository repo = mock(UserMessageRepository.class);
        final WechatStudentBindingRepository bindings = mock(WechatStudentBindingRepository.class);
        final CurrentUser current = mock(CurrentUser.class);
        final UserAccount user;
        final Activity activity = new Activity();
        final UserMessageService service;
        Fixture(UserAccount.Role role) {
            user = new UserAccount("本人", null, null, role); user.id = 1L;
            activity.id = 10L; activity.organizer = user;
            when(current.require(UserAccount.Role.ADMIN, UserAccount.Role.ORGANIZER)).thenReturn(user);
            service = new UserMessageService(repo, current, bindings, "app", mock(UserRepository.class));
        }
        UserMessage message(Long id, Long identity, Activity activity, String event) {
            var message = new UserMessage(user, identity, activity, UserMessage.Kind.REVIEW_APPROVED, "通知", "内容", event);
            message.id = id;
            return message;
        }
    }
}
