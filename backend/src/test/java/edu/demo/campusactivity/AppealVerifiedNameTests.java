package edu.demo.campusactivity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppealVerifiedNameTests {
    @Test
    void approvalUsesCertificateNameAndKeepsPreviousNameInConclusion() {
        var fixture = new Fixture();
        fixture.service.approve(1L, fixture.admin, fixture.request(false));
        assertEquals("证件姓名", fixture.target.name);
        assertEquals(BindingAppeal.Status.APPROVED, fixture.appeal.status);
        assertTrue(fixture.appeal.reviewNote.contains("原登记姓名【旧登记名】"));
        assertTrue(fixture.appeal.reviewNote.contains("核对后姓名【证件姓名】"));
        verify(fixture.bindings).saveAndFlush(any(WechatStudentBinding.class));
    }

    @Test
    void existingBindingStillRequiresExplicitReplacementAndDoesNotChangeNameOnRejection() {
        var fixture = new Fixture();
        var otherIdentity = new WechatIdentity("test-app", "other-openid");
        otherIdentity.id = 3L;
        when(fixture.bindings.lockActiveByStudent("test-app", 2L))
            .thenReturn(Optional.of(new WechatStudentBinding(otherIdentity, fixture.target)));
        assertThrows(ApiException.class, () -> fixture.service.approve(1L, fixture.admin, fixture.request(false)));
        assertEquals("旧登记名", fixture.target.name);
        assertEquals(BindingAppeal.Status.PENDING, fixture.appeal.status);
        verify(fixture.bindings, never()).saveAndFlush(any());
    }

    private static class Fixture {
        final WechatStudentBindingRepository bindings = mock(WechatStudentBindingRepository.class);
        final UserAccount target = UserAccount.selfRegisteredStudent("旧登记名", "20260000000001");
        final UserAccount admin = new UserAccount("审核员", null, null, UserAccount.Role.ADMIN);
        final BindingAppeal appeal;
        final BindingAppealService service;

        Fixture() {
            target.id = 2L;
            admin.id = 9L;
            var identity = new WechatIdentity("test-app", "applicant-openid");
            identity.id = 1L;
            appeal = new BindingAppeal(identity, null, BindingAppeal.Type.STUDENT_NUMBER_OCCUPIED,
                target.studentNumber, "申请核对", "fixture-key", Instant.now());
            appeal.id = 1L;
            var identities = mock(WechatIdentityRepository.class);
            var appeals = mock(BindingAppealRepository.class);
            var users = mock(UserRepository.class);
            service = spy(new BindingAppealService("test-app", true, identities, bindings,
                mock(AppealChallengeRepository.class), appeals, mock(PrivateAppealEvidenceStore.class),
                users, mock(ActivityRepository.class), mock(RegistrationRepository.class),
                mock(CheckinRepository.class), mock(MiniappAccessTokenRepository.class),
                mock(ParticipationAttributionReviewRepository.class), mock(UserSecurityAuditRepository.class), mock(UserMessageService.class)));
            when(appeals.lockById(1L)).thenReturn(Optional.of(appeal));
            when(identities.lockById(1L)).thenReturn(Optional.of(identity));
            when(users.lockUserById(2L)).thenReturn(Optional.of(target));
            doReturn(new BindingAppealService.ImpactView(1L, 2L, List.of(), false, List.of()))
                .when(service).impact(1L);
        }

        AdminBindingAppealController.ApproveRequest request(boolean replace) {
            return new AdminBindingAppealController.ApproveRequest(" 证件姓名 ", "管理员已核对学生证", replace, List.of());
        }
    }
}
