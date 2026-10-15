package edu.demo.campusactivity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RegistrationService {
    private final ActivityRepository activities;
    private final RegistrationRepository registrations;
    private final CheckinRepository checkins;
    private final CurrentUser current;
    private final ActivityService activityService;
    private final UserMessageService messages;

    RegistrationService(ActivityRepository activities, RegistrationRepository registrations,
                        CheckinRepository checkins, CurrentUser current, ActivityService activityService,
                        UserMessageService messages) {
        this.activities = activities;
        this.registrations = registrations;
        this.checkins = checkins;
        this.current = current;
        this.activityService = activityService;
        this.messages = messages;
    }

    @Transactional
    RegistrationView register(Long activityId) {
        UserAccount student = current.require(UserAccount.Role.STUDENT);
        MiniappAttribution attribution = current.miniappAttribution();
        Activity activity = activities.lockById(activityId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在"));
        Instant now = Instant.now();
        if (activity.status != Activity.Status.PUBLISHED || now.isBefore(activity.registrationOpensAt)
            || !now.isBefore(activity.registrationClosesAt)) {
            throw new ApiException(HttpStatus.CONFLICT, "当前不在报名时间内");
        }
        Registration existing = registrations.findByActivityIdAndStudentId(activityId, student.getId()).orElse(null);
        if (existing != null && attribution != null && !attribution.identityId().equals(existing.identityId)) {
            throw new ApiException(HttpStatus.CONFLICT, "该活动的历史报名归属待管理员核实");
        }
        if (existing != null && existing.status == Registration.Status.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "你已报名该活动");
        }
        if (activity.registeredCount >= activity.capacity) {
            throw new ApiException(HttpStatus.CONFLICT, "活动名额已满");
        }
        Registration registration = existing == null ? new Registration(activity, student) : existing;
        registration.status = Registration.Status.ACTIVE;
        registration.registeredAt = now;
        registration.cancelledAt = null;
        if (attribution != null) {
            registration.identityId = attribution.identityId();
            registration.bindingId = attribution.bindingId();
        }
        activity.registeredCount++;
        Registration saved = registrations.saveAndFlush(registration);
        messages.send(student, activity, UserMessage.Kind.REGISTRATION_SUCCESS,
            "报名成功", "你已报名“" + activity.title + "”。", "registration:" + saved.id + ":" + now);
        return RegistrationView.of(saved, null);
    }

    @Transactional
    void cancel(Long activityId) {
        UserAccount student = current.require(UserAccount.Role.STUDENT);
        MiniappAttribution attribution = current.miniappAttribution();
        Activity activity = activities.lockById(activityId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在"));
        Registration registration = registrations.findByActivityIdAndStudentId(activityId, student.getId())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "没有报名记录"));
        if (attribution != null && !attribution.identityId().equals(registration.identityId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "没有当前微信账号的报名记录");
        }
        if (registration.status != Registration.Status.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "报名已取消");
        }
        if (activity.status != Activity.Status.PUBLISHED) {
            throw new ApiException(HttpStatus.CONFLICT, "活动已取消，报名记录将保留");
        }
        if (!Instant.now().isBefore(activity.startsAt) || checkins.existsByActivityIdAndStudentId(activityId, student.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "活动已开始或已经签到，不能取消报名");
        }
        registration.status = Registration.Status.CANCELLED;
        registration.cancelledAt = Instant.now();
        activity.registeredCount--;
    }

    @Transactional(readOnly = true)
    List<RegistrationView> mine() {
        UserAccount student = current.require(UserAccount.Role.STUDENT);
        MiniappAttribution attribution = current.miniappAttribution();
        List<Registration> rows = attribution == null
            ? registrations.findByStudentIdOrderByRegisteredAtDesc(student.getId())
            : registrations.findByStudentIdAndIdentityIdOrderByRegisteredAtDesc(
                student.getId(), attribution.identityId());
        return rows.stream()
            .map(r -> RegistrationView.of(r, matchingCheckin(r)))
            .toList();
    }

    @Transactional(readOnly = true)
    List<RegistrationView> forActivity(Long activityId) {
        activityService.owned(activityId);
        return listForActivity(activityId);
    }

    @Transactional(readOnly = true)
    List<RegistrationView> forAdmin(Long activityId) {
        current.require(UserAccount.Role.ADMIN);
        activityService.find(activityId);
        return listForActivity(activityId);
    }

    private List<RegistrationView> listForActivity(Long activityId) {
        return registrations.findByActivityIdOrderByRegisteredAtAsc(activityId).stream()
            .map(r -> RegistrationView.forManagement(r, matchingCheckin(r)))
            .toList();
    }

    private Checkin matchingCheckin(Registration registration) {
        Checkin checkin = checkins.findByActivityIdAndStudentId(
            registration.activity.getId(), registration.student.getId()).orElse(null);
        return checkin != null
            && Objects.equals(registration.identityId, checkin.identityId)
            && Objects.equals(registration.bindingId, checkin.bindingId) ? checkin : null;
    }
}
