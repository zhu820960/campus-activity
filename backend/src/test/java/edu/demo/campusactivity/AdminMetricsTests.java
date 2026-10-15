package edu.demo.campusactivity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:admin_metrics;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AdminMetricsTests {
    @Autowired AdminMetrics metrics;
    @Autowired UserRepository users;
    @Autowired ActivityRepository activities;
    @Autowired RegistrationRepository registrations;
    @Autowired CheckinRepository checkins;

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    private void as(UserAccount account) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(account.id, null, List.of()));
    }

    @Test
    void reportsCurrentEffectiveRegistrationAndActualCheckinByCampusDay() {
        UserAccount admin = users.saveAndFlush(new UserAccount("管理员", "metrics-admin@test.local",
            "unused", UserAccount.Role.ADMIN));
        UserAccount student = users.saveAndFlush(new UserAccount("学生", "metrics-student@test.local",
            "unused", UserAccount.Role.STUDENT));
        Activity activity = new Activity();
        activity.organizer = admin;
        activity.title = "统计测试";
        activity.description = "测试";
        activity.location = "测试地点";
        activity.startsAt = Instant.now();
        activity.endsAt = Instant.now().plusSeconds(3600);
        activity.registrationOpensAt = Instant.now().minusSeconds(3600);
        activity.registrationClosesAt = Instant.now();
        activity.checkinOpensAt = Instant.now();
        activity.checkinClosesAt = Instant.now().plusSeconds(3600);
        activity.capacity = 10;
        activities.saveAndFlush(activity);
        Registration registration = registrations.saveAndFlush(new Registration(activity, student));
        Checkin checkin = checkins.saveAndFlush(new Checkin(activity, student, null, Checkin.Method.QR, null));
        as(student);
        assertEquals(403, assertThrows(ApiException.class, () -> metrics.daily(7)).status.value());
        as(admin);
        assertEquals(400, assertThrows(ApiException.class, () -> metrics.daily(31)).status.value());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        DailyMetric current = metrics.daily(7).get(6);
        assertEquals(today, current.day());
        assertEquals(1, current.registered());
        assertEquals(1, current.checked());
        registration.status = Registration.Status.CANCELLED;
        registrations.saveAndFlush(registration);
        current = metrics.daily(7).get(6);
        assertEquals(0, current.registered());
        assertEquals(1, current.checked());
    }
}
