package edu.demo.campusactivity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

record DailyMetric(LocalDate day, long registered, long checked) {}

@Service
class AdminMetrics {
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");
    private final RegistrationRepository registrations;
    private final CheckinRepository checkins;
    private final CurrentUser current;

    AdminMetrics(RegistrationRepository registrations, CheckinRepository checkins, CurrentUser current) {
        this.registrations = registrations;
        this.checkins = checkins;
        this.current = current;
    }

    @Transactional(readOnly = true)
    List<DailyMetric> daily(int days) {
        current.require(UserAccount.Role.ADMIN);
        if (days < 1 || days > 30) throw new ApiException(HttpStatus.BAD_REQUEST, "统计天数须在1到30之间");
        LocalDate today = LocalDate.now(CAMPUS_ZONE);
        LocalDate first = today.minusDays(days - 1);
        Instant start = first.atStartOfDay(CAMPUS_ZONE).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(CAMPUS_ZONE).toInstant();
        Map<LocalDate, Long> registered = registrations.activeRegistrationTimes(
            Registration.Status.ACTIVE, start, end).stream().collect(Collectors.groupingBy(
                time -> time.atZone(CAMPUS_ZONE).toLocalDate(), Collectors.counting()));
        Map<LocalDate, Long> checked = checkins.checkinTimes(start, end).stream().collect(Collectors.groupingBy(
            time -> time.atZone(CAMPUS_ZONE).toLocalDate(), Collectors.counting()));
        List<DailyMetric> result = new ArrayList<>(days);
        for (int offset = 0; offset < days; offset++) {
            LocalDate day = first.plusDays(offset);
            result.add(new DailyMetric(day, registered.getOrDefault(day, 0L), checked.getOrDefault(day, 0L)));
        }
        return result;
    }
}
