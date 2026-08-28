package com.myroboot.support.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthRateLimitServiceTest {

    @Test
    void blocksLoginAfterRepeatedFailures() {
        AuthRateLimitService service = new AuthRateLimitService();
        String key = "127.0.0.1|admin";

        for (int i = 0; i < 8; i++) service.recordLoginFailure(key);

        assertThrows(AuthRateLimitService.RateLimitException.class, () -> service.checkLoginAllowed(key));
    }

    @Test
    void successfulLoginClearsFailureState() {
        AuthRateLimitService service = new AuthRateLimitService();
        String key = "127.0.0.1|customer";

        for (int i = 0; i < 7; i++) service.recordLoginFailure(key);
        service.recordLoginSuccess(key);

        assertDoesNotThrow(() -> service.checkLoginAllowed(key));
    }

    @Test
    void limitsVerificationRequestsPerIp() {
        AuthRateLimitService service = new AuthRateLimitService();
        String ip = "10.0.0.10";

        for (int i = 0; i < 20; i++) service.checkAndRecordCodeRequest(ip);

        assertThrows(AuthRateLimitService.RateLimitException.class, () -> service.checkAndRecordCodeRequest(ip));
    }
}
