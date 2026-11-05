package com.flowforge.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookRateLimiterTest {
    @Test
    void allowsSixtyRequestsPerKeyAndRejectsTheNextUntilWindowExpires() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC);
        WebhookRateLimiter limiter = new WebhookRateLimiter(clock);

        for (int request = 0; request < 60; request++) {
            assertThat(limiter.allow("webhook:127.0.0.1")).isTrue();
        }
        assertThat(limiter.allow("webhook:127.0.0.1")).isFalse();
        assertThat(limiter.allow("other-webhook:127.0.0.1")).isTrue();
    }
}
