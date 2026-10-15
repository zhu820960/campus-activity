package edu.demo.campusactivity;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WechatCodeExchangeTests {
    @Test
    void parsesOnlyValidOpenIdWithoutExposingSessionKey() {
        WechatCodeExchange gateway = new WechatCodeExchange("test-app", "test-secret", new ObjectMapper());
        assertEquals("student-openid", gateway.readOpenId("{\"openid\":\"student-openid\",\"session_key\":\"ignored\"}"));
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ApiException.class, () -> gateway.readOpenId("{\"errcode\":40029,\"errmsg\":\"invalid code\"}")).status);
        assertEquals(HttpStatus.BAD_GATEWAY,
            assertThrows(ApiException.class, () -> gateway.readOpenId("not-json")).status);
    }

    @Test
    void missingSecretStopsBeforeNetworkCall() {
        WechatCodeExchange gateway = new WechatCodeExchange("test-app", "", new ObjectMapper());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(ApiException.class, () -> gateway.exchange("temporary-code")).status);
    }
}
