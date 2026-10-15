package edu.demo.campusactivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AiDraftResponseTests {
    @Test
    void mapsUpstreamStatusWithoutLeakingResponseDetails() {
        assertDoesNotThrow(() -> AiDraftController.requireSuccess(200));
        assertEquals(503, assertThrows(ApiException.class,
            () -> AiDraftController.requireSuccess(401)).status.value());
        assertEquals(503, assertThrows(ApiException.class,
            () -> AiDraftController.requireSuccess(402)).status.value());
        assertEquals(429, assertThrows(ApiException.class,
            () -> AiDraftController.requireSuccess(429)).status.value());
        assertEquals(502, assertThrows(ApiException.class,
            () -> AiDraftController.requireSuccess(500)).status.value());
    }
}
