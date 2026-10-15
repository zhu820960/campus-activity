package edu.demo.campusactivity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MiniappAuthService {
    record StudentView(Long id, String name, String email, String studentNumber, String loginName,
                       boolean webCredentialsActive, boolean hasAvatar, Set<UserAccount.Role> roles) {
        static StudentView of(UserAccount account) {
            return new StudentView(account.getId(), account.getName(), account.getEmail(),
                account.getStudentNumber(), account.getLoginName(), account.passwordHash != null,
                account.getAvatarKey() != null,
                Set.copyOf(account.getRoles()));
        }
    }

    record LoginResult(String token, Instant expiresAt, StudentView user) {
        @Override public String toString() { return "LoginResult[token=REDACTED]"; }
    }

    record AuthenticatedStudent(UserAccount student, MiniappAttribution attribution) {}

    private final String appId;
    private final WechatStudentBindingRepository bindings;
    private final WechatIdentityRepository identities;
    private final MiniappAccessTokenRepository tokens;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final SecureRandom random = new SecureRandom();

    MiniappAuthService(@Value("${app.wechat.app-id:}") String appId,
                       WechatStudentBindingRepository bindings,
                       WechatIdentityRepository identities,
                       MiniappAccessTokenRepository tokens, UserRepository users,
                       PasswordEncoder passwords) {
        this.appId = appId;
        this.bindings = bindings;
        this.identities = identities;
        this.tokens = tokens;
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional
    LoginResult login(String openId) {
        requireConfigured();
        requireOpenId(openId);
        WechatStudentBinding binding = bindings.findByAppIdAndOpenIdAndUnboundAtIsNull(appId, openId)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "微信账号尚未绑定学生账号"));
        requireStudent(binding.getStudent());
        return issue(binding);
    }

    @Transactional
    LoginResult bind(String openId, String email, String password) {
        requireConfigured();
        requireOpenId(openId);
        UserAccount student = users.lockByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误"));
        if (student.passwordHash == null || !passwords.matches(password, student.passwordHash)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误");
        }
        requireStudent(student);
        if (bindings.findByAppIdAndOpenIdAndUnboundAtIsNull(appId, openId).isPresent()
            || bindings.findByAppIdAndStudentIdAndUnboundAtIsNull(appId, student.id).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "微信账号或学生账号已绑定，请直接登录");
        }
        requireNoUnboundHistory(openId);
        try {
            WechatIdentity identity = identities.findByAppIdAndOpenId(appId, openId)
                .orElseGet(() -> identities.saveAndFlush(new WechatIdentity(appId, openId)));
            WechatStudentBinding binding = bindings.saveAndFlush(new WechatStudentBinding(identity, student));
            return issue(binding);
        } catch (DataIntegrityViolationException error) {
            throw new ApiException(HttpStatus.CONFLICT, "微信账号或学生账号已绑定，请直接登录");
        }
    }

    @Transactional
    LoginResult bindStudent(String openId, String studentNumber, String name) {
        requireConfigured();
        requireOpenId(openId);
        if (studentNumber == null || !studentNumber.matches("[0-9]{14}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "学号须为14位数字");
        }
        String realName = name == null ? "" : name.trim();
        if (realName.isEmpty() || realName.length() > 80 || realName.chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请填写有效的真实姓名");
        }
        if (bindings.findByAppIdAndOpenIdAndUnboundAtIsNull(appId, openId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "该微信账号已绑定，请直接登录");
        }
        requireNoUnboundHistory(openId);
        if (users.findByStudentNumber(studentNumber).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "该学号已登记，请联系管理员核对");
        }
        try {
            UserAccount student = users.saveAndFlush(UserAccount.selfRegisteredStudent(realName, studentNumber));
            WechatIdentity identity = identities.findByAppIdAndOpenId(appId, openId)
                .orElseGet(() -> identities.saveAndFlush(new WechatIdentity(appId, openId)));
            WechatStudentBinding binding = bindings.saveAndFlush(new WechatStudentBinding(identity, student));
            return issue(binding);
        } catch (DataIntegrityViolationException error) {
            throw new ApiException(HttpStatus.CONFLICT, "微信账号或学号已登记，请联系管理员核对");
        }
    }

    @Transactional
    StudentView activateWebCredentials(Long studentId, String openId, String password) {
        requireConfigured();
        requireOpenId(openId);
        if (password == null || !password.matches("[\\x21-\\x7E]{8,64}")
            || !password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "密码须为8至64位英文字母、数字或符号，且包含字母和数字");
        }
        UserAccount student = users.lockUserById(studentId)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "学生账号不存在"));
        requireStudent(student);
        if (!student.roles.contains(UserAccount.Role.ORGANIZER) || student.roles.contains(UserAccount.Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "该账号没有组织者权限");
        }
        if (student.studentNumber == null || !student.studentNumber.matches("[0-9]{14}")) {
            throw new ApiException(HttpStatus.CONFLICT, "请先绑定14位学号，再设置 Web 登录密码");
        }
        WechatStudentBinding binding = bindings.findByAppIdAndOpenIdAndUnboundAtIsNull(appId, openId)
            .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "微信身份与当前账号不一致"));
        if (!student.id.equals(binding.getStudent().id)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "微信身份与当前账号不一致");
        }
        if (student.passwordHash != null) {
            throw new ApiException(HttpStatus.CONFLICT, "该组织者已有 Web 登录凭据");
        }
        student.passwordHash = passwords.encode(password);
        users.saveAndFlush(student);
        return StudentView.of(student);
    }

    @Transactional(readOnly = true)
    UserAccount authenticate(String rawToken) {
        return authenticateWithAttribution(rawToken).student();
    }

    @Transactional(readOnly = true)
    AuthenticatedStudent authenticateWithAttribution(String rawToken) {
        MiniappAccessToken token = validToken(rawToken);
        WechatStudentBinding binding = token.getBinding();
        UserAccount student = binding.getStudent();
        return new AuthenticatedStudent(student,
            new MiniappAttribution(student.getId(), binding.getIdentity().getId(), binding.getId()));
    }

    @Transactional
    void revoke(String rawToken) {
        MiniappAccessToken token = validToken(rawToken);
        token.revokedAt = Instant.now();
    }

    private MiniappAccessToken validToken(String rawToken) {
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "小程序登录已失效");
        }
        MiniappAccessToken token = tokens.findByTokenHash(hash(rawToken))
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "小程序登录已失效"));
        if (token.revokedAt != null || !Instant.now().isBefore(token.expiresAt)
            || !appId.equals(token.getBinding().getAppId())
            || token.getBinding().getUnboundAt() != null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "小程序登录已失效");
        }
        requireStudent(token.getBinding().getStudent());
        return token;
    }

    private LoginResult issue(WechatStudentBinding binding) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        MiniappAccessToken saved = tokens.save(new MiniappAccessToken(binding, hash(raw), Instant.now()));
        return new LoginResult(raw, saved.expiresAt, StudentView.of(binding.getStudent()));
    }

    private void requireConfigured() {
        if (appId.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "微信小程序 AppID 尚未配置");
        }
    }

    private void requireOpenId(String openId) {
        if (openId == null || openId.isBlank() || openId.length() > 128) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "微信身份无效");
        }
    }

    private void requireStudent(UserAccount account) {
        if (account == null || !account.getRoles().contains(UserAccount.Role.STUDENT)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "该账号不是学生账号");
        }
    }

    private void requireNoUnboundHistory(String openId) {
        if (bindings.existsByAppIdAndOpenIdAndUnboundAtIsNotNull(appId, openId)) {
            throw new ApiException(HttpStatus.CONFLICT, "该微信账号有解绑记录，请先提交账号申诉");
        }
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }
}
