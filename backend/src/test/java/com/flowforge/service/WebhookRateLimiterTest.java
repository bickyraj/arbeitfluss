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
    @Test
    void expiredWindowResetsAndCapacityRecoversWithoutUnboundedState() {
        java.util.concurrent.atomic.AtomicLong millis = new java.util.concurrent.atomic.AtomicLong(0);
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        };
        WebhookRateLimiter limiter = new WebhookRateLimiter(clock);
        for (int i=0; i<60; i++) assertThat(limiter.allow("first")).isTrue();
        assertThat(limiter.allow("first")).isFalse();
        for (int i=1; i<10_000; i++) assertThat(limiter.allow("key-"+i)).isTrue();
        assertThat(limiter.allow("overflow")).isFalse();
        millis.set(60_000);
        assertThat(limiter.allow("first")).isTrue();
        assertThat(limiter.allow("overflow")).isTrue();
    }

    @Test
    void concurrentRequestsCannotExceedPerKeyBudget() throws Exception {
        WebhookRateLimiter limiter = new WebhookRateLimiter();
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i=0; i<100; i++) futures.add(executor.submit(() -> {
                if (limiter.allow("shared")) accepted.incrementAndGet();
            }));
            for (var future : futures) future.get();
        }
        assertThat(accepted.get()).isEqualTo(60);
    }
}
