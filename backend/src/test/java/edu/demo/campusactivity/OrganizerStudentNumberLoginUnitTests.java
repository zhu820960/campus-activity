package edu.demo.campusactivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OrganizerStudentNumberLoginUnitTests {
    private final WechatStudentBindingRepository bindings = mock(WechatStudentBindingRepository.class);
    private final WechatIdentityRepository identities = mock(WechatIdentityRepository.class);
    private final MiniappAccessTokenRepository tokens = mock(MiniappAccessTokenRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final MiniappAuthService auth = new MiniappAuthService("test-app", bindings, identities, tokens, users, passwords);

    @Test
    void boundOrganizerSetsPasswordWithoutNewUsername() {
        UserAccount organizer = organizer("00000000000011");
        when(users.lockUserById(11L)).thenReturn(Optional.of(organizer));
        when(bindings.findByAppIdAndOpenIdAndUnboundAtIsNull("test-app", "openid"))
            .thenReturn(Optional.of(new WechatStudentBinding(new WechatIdentity("test-app", "openid"), organizer)));
        when(passwords.encode("goodPassword123")).thenReturn("encoded");

        MiniappAuthService.StudentView result = auth.activateWebCredentials(11L, "openid", "goodPassword123");

        assertEquals("00000000000011", result.studentNumber());
        assertTrue(result.webCredentialsActive());
        assertNull(result.loginName());
        assertEquals("encoded", organizer.passwordHash);
        verify(users).saveAndFlush(organizer);
    }

    @Test
    void organizerWithoutStudentNumberCannotActivate() {
        UserAccount organizer = organizer(null);
        when(users.lockUserById(11L)).thenReturn(Optional.of(organizer));

        ApiException error = assertThrows(ApiException.class,
            () -> auth.activateWebCredentials(11L, "openid", "goodPassword123"));

        assertEquals(HttpStatus.CONFLICT, error.status);
        assertNull(organizer.passwordHash);
    }

    @Test
    void wrongWechatIdentityCannotActivate() {
        UserAccount organizer = organizer("00000000000011");
        when(users.lockUserById(11L)).thenReturn(Optional.of(organizer));

        ApiException error = assertThrows(ApiException.class,
            () -> auth.activateWebCredentials(11L, "other-openid", "goodPassword123"));

        assertEquals(HttpStatus.FORBIDDEN, error.status);
    }

    @Test
    void eightCharacterPasswordIsAccepted() {
        UserAccount organizer = organizer("00000000000011");
        when(users.lockUserById(11L)).thenReturn(Optional.of(organizer));
        when(bindings.findByAppIdAndOpenIdAndUnboundAtIsNull("test-app", "openid"))
            .thenReturn(Optional.of(new WechatStudentBinding(new WechatIdentity("test-app", "openid"), organizer)));
        when(passwords.encode("abc12345")).thenReturn("encoded");

        auth.activateWebCredentials(11L, "openid", "abc12345");

        assertEquals("encoded", organizer.passwordHash);
    }

    @Test
    void shortOrNonAsciiPasswordIsRejected() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
            () -> auth.activateWebCredentials(11L, "openid", "abc1234")).status);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
            () -> auth.activateWebCredentials(11L, "openid", "abcd123中")).status);
    }

    @Test
    void webLoginAcceptsOrganizerStudentNumber() {
        UserAccount organizer = organizer("00000000000011");
        organizer.passwordHash = "encoded";
        when(users.findByStudentNumber("00000000000011")).thenReturn(Optional.of(organizer));
        when(passwords.matches("goodPassword123", "encoded")).thenReturn(true);
        AuthController controller = new AuthController(users, passwords,
            mock(SecurityContextRepository.class), mock(CurrentUser.class));
        try {
            AuthController.UserView loggedIn = controller.login(
                new AuthController.LoginRequest("00000000000011", "goodPassword123"),
                new MockHttpServletRequest(), new MockHttpServletResponse());
            assertEquals(11L, loggedIn.id());
            verify(users).findByStudentNumber("00000000000011");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static UserAccount organizer(String studentNumber) {
        UserAccount organizer = new UserAccount("组织者", null, null, UserAccount.Role.STUDENT);
        organizer.id = 11L;
        organizer.studentNumber = studentNumber;
        organizer.roles.add(UserAccount.Role.ORGANIZER);
        return organizer;
    }
}
