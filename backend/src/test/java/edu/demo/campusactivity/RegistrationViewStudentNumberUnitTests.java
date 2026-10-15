package edu.demo.campusactivity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RegistrationViewStudentNumberUnitTests {
    @Test
    void managementRosterIncludesTheStudentNumberWithoutChangingTheRecord() {
        Activity activity = new Activity();
        activity.id = 42L;
        activity.title = "测试活动";
        UserAccount student = UserAccount.selfRegisteredStudent("测试学生", "00000000000005");
        student.id = 5L;
        Registration registration = new Registration(activity, student);
        registration.id = 11L;

        RegistrationView row = RegistrationView.forManagement(registration, null);

        assertEquals("00000000000005", row.studentNumber());
        assertEquals(5L, row.studentId());
        assertEquals("00000000000005", student.studentNumber);
    }
}
