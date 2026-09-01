package com.myroboot.support.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthRateLimitService {
    private static final int LOGIN_FAILURE_LIMIT = 8;
    private static final long LOGIN_WINDOW_SECONDS = 10 * 60;
    private static final long LOGIN_BLOCK_SECONDS = 15 * 60;
    private static final int CODE_IP_LIMIT = 20;
    private static final long CODE_IP_WINDOW_SECONDS = 60 * 60;

    private final Map<String, LoginState> loginStates = new ConcurrentHashMap<>();
    private final Map<String, CounterWindow> codeIpWindows = new ConcurrentHashMap<>();

    public void checkLoginAllowed(String key) {
        long now = Instant.now().getEpochSecond();
        LoginState state = loginStates.get(key);
        if (state == null) return;
        if (state.blockUntil > now) {
            long minutes = Math.max(1, (state.blockUntil - now + 59) / 60);
            throw new RateLimitException("登录失败次数过多，请 " + minutes + " 分钟后再试");
        }
        if (state.windowStart + LOGIN_WINDOW_SECONDS <= now) loginStates.remove(key, state);
    }

    public void recordLoginFailure(String key) {
        long now = Instant.now().getEpochSecond();
        loginStates.compute(key, (ignored, old) -> {
            LoginState state = old;
            if (state == null || state.windowStart + LOGIN_WINDOW_SECONDS <= now) {
                state = new LoginState(now, 0, 0);
            }
            int failures = state.failures + 1;
            long blockUntil = failures >= LOGIN_FAILURE_LIMIT ? now + LOGIN_BLOCK_SECONDS : state.blockUntil;
            return new LoginState(state.windowStart, failures, blockUntil);
        });
        cleanupOccasionally();
    }

    public void recordLoginSuccess(String key) {
        loginStates.remove(key);
    }

    public void checkAndRecordCodeRequest(String ip) {
        long now = Instant.now().getEpochSecond();
        CounterWindow value = codeIpWindows.compute(ip, (ignored, old) -> {
            if (old == null || old.windowStart + CODE_IP_WINDOW_SECONDS <= now) {
                return new CounterWindow(now, 1);
            }
            return new CounterWindow(old.windowStart, old.count + 1);
        });
        if (value.count > CODE_IP_LIMIT) {
            throw new RateLimitException("验证码请求过于频繁，请稍后再试");
        }
        cleanupOccasionally();
    }

    private void cleanupOccasionally() {
        if ((loginStates.size() + codeIpWindows.size()) < 2000) return;
        long now = Instant.now().getEpochSecond();
        loginStates.entrySet().removeIf(e -> e.getValue().blockUntil <= now && e.getValue().windowStart + LOGIN_WINDOW_SECONDS <= now);
        codeIpWindows.entrySet().removeIf(e -> e.getValue().windowStart + CODE_IP_WINDOW_SECONDS <= now);
    }

    private record LoginState(long windowStart, int failures, long blockUntil) {}
    private record CounterWindow(long windowStart, int count) {}

    public static class RateLimitException extends RuntimeException {
        public RateLimitException(String message) {
            super(message);
        }
    }
}
