package edu.demo.campusactivity;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Routing/validation checks without a database; authorization remains in shared services. */
class WebOrganizerMutationControllerTests {
    private final ActivityPublishedChangeService changes = mock(ActivityPublishedChangeService.class);
    private final ActivityCancellationService cancellations = mock(ActivityCancellationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
        new MiniappActivityChangeController(changes), new MiniappActivityCancellationController(cancellations)).build();

    @Test void bothClientsUseTheSamePendingAndCancellationServices() throws Exception {
        for (String prefix : new String[]{"/api/organizer", "/api/miniapp/organizer"}) {
            mvc.perform(get(prefix + "/activities/9/changes")).andExpect(status().isOk());
            mvc.perform(get(prefix + "/activities/9/cancellation")).andExpect(status().isOk());
            mvc.perform(post(prefix + "/activities/9/cancellation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"会场取消开放\"}")).andExpect(status().isOk());
        }
        verify(changes, times(2)).pendingForOrganizer(9L);
        verify(cancellations, times(2)).pendingForOrganizer(9L);
        verify(cancellations, times(2)).request(9L, "会场取消开放");
    }

    @Test void webChangeSubmitsTheProposalAndReason() throws Exception {
        mvc.perform(post("/api/organizer/activities/9/changes").contentType(MediaType.APPLICATION_JSON).content("""
            {"reason":"修改会场", "proposed":{"title":"活动","description":"正文","location":"会场",
            "capacity":30,"startsAt":"2027-01-01T06:00:00Z","endsAt":"2027-01-01T08:00:00Z",
            "registrationOpensAt":"2026-10-15T02:00:00Z","registrationClosesAt":"2027-01-01T05:30:00Z",
            "checkinOpensAt":"2027-01-01T05:30:00Z","checkinClosesAt":"2027-01-01T06:30:00Z"}}
            """)).andExpect(status().isOk());
        verify(changes).request(eq(9L), any(ActivityService.ActivityInput.class), eq("修改会场"));
    }

    @Test void invalidRequestsNeverReachTheServices() throws Exception {
        mvc.perform(post("/api/organizer/activities/9/cancellation").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/organizer/activities/9/changes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"修改\",\"proposed\":null}")).andExpect(status().isBadRequest());
        verifyNoInteractions(changes, cancellations);
    }
}
