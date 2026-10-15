package edu.demo.campusactivity;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

record UserMessageView(Long id, UserMessage.Kind kind, String title, String body,
                       Long activityId, Instant createdAt, Instant readAt) {
    static UserMessageView of(UserMessage message) {
        return new UserMessageView(message.id, message.kind, message.title, message.body,
            message.activity == null ? null : message.activity.getId(), message.createdAt, message.readAt);
    }
}

@Service
class UserMessageService {
    private final UserMessageRepository messages;
    private final CurrentUser current;
    private final WechatStudentBindingRepository bindings;
    private final String appId;
    private final UserRepository users;

    UserMessageService(UserMessageRepository messages, CurrentUser current,
                       WechatStudentBindingRepository bindings,
                       @org.springframework.beans.factory.annotation.Value("${app.wechat.app-id:}") String appId,
                       UserRepository users) {
        this.messages = messages;
        this.current = current;
        this.bindings = bindings;
        this.appId = appId;
        this.users = users;
    }

    @Transactional
    void send(UserAccount recipient, Activity activity, UserMessage.Kind kind,
              String title, String body, String eventKey) {
        Long identityId = activeIdentity(recipient.getId());
        String storedKey = identityId == null && recipient.getRoles().contains(UserAccount.Role.ORGANIZER)
            ? "web-account:" + eventKey : eventKey;
        sendToIdentity(recipient, identityId, activity, kind, title, body, storedKey);
        if (kind == UserMessage.Kind.REVIEW_APPROVED || kind == UserMessage.Kind.REVIEW_REJECTED) {
            notifyAdmins(activity, kind, title, body, eventKey);
        }
    }

    @Transactional
    void notifyAdmins(Activity activity, UserMessage.Kind kind, String title, String body, String eventKey) {
        for (UserAccount admin : users.findAllByOrderByCreatedAtDesc()) {
            if (admin.getRoles().contains(UserAccount.Role.ADMIN)) {
                sendToIdentity(admin, null, activity, kind, title, body, "admin:" + admin.getId() + ":" + eventKey);
            }
        }
    }

    record WebNotification(Long id, String title, String body, Instant createdAt, Instant readAt, String targetPath) {}

    private Long webIdentity(UserAccount user) {
        return user.getRoles().contains(UserAccount.Role.ADMIN) ? null : activeIdentity(user.getId());
    }

    private WebNotification webView(UserMessage message, UserAccount user) {
        boolean admin = user.getRoles().contains(UserAccount.Role.ADMIN);
        String path = "/account?notification=" + message.id;
        if (message.activity != null) {
            if (admin) path = "/admin/activities/" + message.activity.getId();
            else if (message.activity.organizer.getId().equals(user.getId())) path = "/organizer/" + message.activity.getId();
            // Personal participation notifications remain readable but never open another organizer's activity.
        } else if (admin && message.eventKey.startsWith("admin:")) {
            var matcher = java.util.regex.Pattern.compile("^admin:[0-9]+:appeal:([0-9]+):[A-Z]+$").matcher(message.eventKey);
            path = matcher.matches() ? "/admin/users?appeal=" + matcher.group(1) : "/account";
        }
        return new WebNotification(message.id, message.title, message.body, message.createdAt, message.readAt, path);
    }

    @Transactional(readOnly = true)
    List<WebNotification> webMine() {
        UserAccount user = current.require(UserAccount.Role.ADMIN, UserAccount.Role.ORGANIZER);
        Long identityId = webIdentity(user);
        return messages.findTop100ByRecipientIdAndIdentityIdOrderByCreatedAtDesc(user.getId(), identityId)
            .stream().filter(message -> visibleOnWeb(message, user, identityId))
            .map(message -> webView(message, user)).toList();
    }

    private boolean visibleOnWeb(UserMessage message, UserAccount user, Long identityId) {
        return identityId != null || user.getRoles().contains(UserAccount.Role.ADMIN)
            || message.eventKey.startsWith("web-account:");
    }

    @Transactional(readOnly = true)
    long webUnread() {
        UserAccount user = current.require(UserAccount.Role.ADMIN, UserAccount.Role.ORGANIZER);
        Long identityId = webIdentity(user);
        if (identityId == null && !user.getRoles().contains(UserAccount.Role.ADMIN)) {
            return messages.countByRecipientIdAndIdentityIdIsNullAndEventKeyStartingWithAndReadAtIsNull(user.getId(), "web-account:");
        }
        return messages.countByRecipientIdAndIdentityIdAndReadAtIsNull(user.getId(), identityId);
    }

    @Transactional
    WebNotification webRead(Long id) {
        UserAccount user = current.require(UserAccount.Role.ADMIN, UserAccount.Role.ORGANIZER);
        Long identityId = webIdentity(user);
        UserMessage message = messages.findByIdAndRecipientIdAndIdentityId(id, user.getId(), identityId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "通知不存在"));
        if (!visibleOnWeb(message, user, identityId)) throw new ApiException(HttpStatus.NOT_FOUND, "通知不存在");
        if (message.readAt == null) message.readAt = Instant.now();
        return webView(message, user);
    }

    @Transactional
    void sendToIdentity(UserAccount recipient, Long identityId, Activity activity, UserMessage.Kind kind,
                        String title, String body, String eventKey) {
        if (!messages.existsByEventKey(eventKey)) {
            messages.save(new UserMessage(recipient, identityId, activity, kind, title, body, eventKey));
        }
    }

    @Transactional(readOnly = true)
    List<UserMessageView> mine() {
        UserAccount user = current.get();
        Long identityId = currentIdentity(user.id);
        if (identityId == null) return List.of();
        return messages.findTop100ByIdentityIdOrderByCreatedAtDesc(identityId).stream()
            .map(UserMessageView::of).toList();
    }

    @Transactional(readOnly = true)
    long unread() {
        UserAccount user = current.get();
        Long identityId = currentIdentity(user.id);
        return identityId == null ? 0
            : messages.countByIdentityIdAndReadAtIsNull(identityId);
    }

    @Transactional
    UserMessageView read(Long id) {
        UserAccount user = current.get();
        Long identityId = currentIdentity(user.id);
        if (identityId == null) throw new ApiException(HttpStatus.NOT_FOUND, "消息不存在");
        UserMessage message = messages.findByIdAndIdentityId(id, identityId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "消息不存在"));
        if (message.readAt == null) message.readAt = Instant.now();
        return UserMessageView.of(message);
    }

    private Long currentIdentity(Long studentId) {
        MiniappAttribution attribution = current.miniappAttribution();
        return attribution == null ? activeIdentity(studentId) : attribution.identityId();
    }

    private Long activeIdentity(Long studentId) {
        return bindings.findActiveIdentityId(appId, studentId).orElse(null);
    }
}

@RestController
@RequestMapping("/api/account/notifications")
class WebNotificationController {
    private final UserMessageService messages;
    WebNotificationController(UserMessageService messages) { this.messages = messages; }
    @GetMapping
    List<UserMessageService.WebNotification> mine() { return messages.webMine(); }
    @GetMapping("/unread-count")
    java.util.Map<String, Long> unread() { return java.util.Map.of("count", messages.webUnread()); }
    @PostMapping("/{id}/read")
    UserMessageService.WebNotification read(@PathVariable Long id) { return messages.webRead(id); }
}

@RestController
@RequestMapping("/api/miniapp/my/messages")
class MiniappUserMessageController {
    private final UserMessageService messages;
    private final CurrentUser current;

    MiniappUserMessageController(UserMessageService messages, CurrentUser current) {
        this.messages = messages;
        this.current = current;
    }

    @GetMapping
    List<UserMessageView> mine() {
        current.require(UserAccount.Role.STUDENT);
        return messages.mine();
    }

    @GetMapping("/unread-count")
    java.util.Map<String, Long> unread() {
        current.require(UserAccount.Role.STUDENT);
        return java.util.Map.of("count", messages.unread());
    }

    @PostMapping("/{id}/read")
    UserMessageView read(@PathVariable Long id) {
        current.require(UserAccount.Role.STUDENT);
        return messages.read(id);
    }
}

@RestController
@RequestMapping("/api/organizer/messages")
class OrganizerUserMessageController {
    private final UserMessageService messages;
    private final CurrentUser current;

    OrganizerUserMessageController(UserMessageService messages, CurrentUser current) {
        this.messages = messages;
        this.current = current;
    }

    @GetMapping
    List<UserMessageView> mine() {
        current.require(UserAccount.Role.ORGANIZER);
        return messages.mine();
    }

    @PostMapping("/{id}/read")
    UserMessageView read(@PathVariable Long id) {
        current.require(UserAccount.Role.ORGANIZER);
        return messages.read(id);
    }
}
