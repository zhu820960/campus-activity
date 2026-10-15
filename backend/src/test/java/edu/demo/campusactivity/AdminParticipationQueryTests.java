package edu.demo.campusactivity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:admin_participation_query;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AdminParticipationQueryTests {
    @Autowired UserRepository users;
    @Autowired ActivityRepository activities;
    @Autowired RegistrationRepository registrations;
    @Autowired CheckinRepository checkins;

    @Test
    @Transactional
    void fetchesOnlyCheckinsBelongingToRequestedRegistrationRows() {
        UserAccount organizer = users.saveAndFlush(new UserAccount("组织者", "query-organizer@test.local",
            "unused", UserAccount.Role.ORGANIZER));
        UserAccount alice = users.saveAndFlush(new UserAccount("甲", "query-alice@test.local",
            "unused", UserAccount.Role.STUDENT));
        UserAccount bob = users.saveAndFlush(new UserAccount("乙", "query-bob@test.local",
            "unused", UserAccount.Role.STUDENT));
        Activity first = activity(organizer, "活动甲");
        Activity second = activity(organizer, "活动乙");
        Registration requested = registrations.saveAndFlush(new Registration(first, alice));
        registrations.saveAndFlush(new Registration(first, bob));
        registrations.saveAndFlush(new Registration(second, alice));
        Checkin match = checkins.saveAndFlush(new Checkin(first, alice, null, Checkin.Method.QR, null));
        checkins.saveAndFlush(new Checkin(first, bob, null, Checkin.Method.QR, null));
        checkins.saveAndFlush(new Checkin(second, alice, null, Checkin.Method.QR, null));

        assertEquals(List.of(match.id), checkins.findForRegistrations(List.of(requested.id))
            .stream().map(c -> c.id).toList());
    }

    private Activity activity(UserAccount organizer, String title) {
        Activity activity = new Activity();
        activity.organizer = organizer;
        activity.title = title;
        activity.description = "测试";
        activity.location = "报告厅";
        Instant now = Instant.now();
        activity.startsAt = now.plusSeconds(3600);
        activity.endsAt = now.plusSeconds(7200);
        activity.registrationOpensAt = now;
        activity.registrationClosesAt = now.plusSeconds(1800);
        activity.checkinOpensAt = now.plusSeconds(3300);
        activity.checkinClosesAt = now.plusSeconds(3900);
        activity.capacity = 10;
        return activities.saveAndFlush(activity);
    }
}
