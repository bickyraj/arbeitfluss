package com.flowforge.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebhookRateLimiter {
    private static final long WINDOW_MILLIS = 60_000;
    private static final int REQUESTS_PER_WINDOW = 60;
    private static final int MAX_KEYS = 10_000;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public WebhookRateLimiter() {
        this(Clock.systemUTC());
    }

    WebhookRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean allow(String key) {
        long now = clock.millis();
        Window current = windows.get(key);
        if (current == null) {
            removeExpired(now);
            if (windows.size() >= MAX_KEYS) {
                return false;
            }
            windows.put(key, new Window(now, 1));
            return true;
        }
        if (now - current.startedAt() >= WINDOW_MILLIS) {
            windows.put(key, new Window(now, 1));
            return true;
        }
        if (current.count() >= REQUESTS_PER_WINDOW) {
            return false;
        }
        windows.put(key, new Window(current.startedAt(), current.count() + 1));
        return true;
    }

    private void removeExpired(long now) {
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().startedAt() >= WINDOW_MILLIS) {
                iterator.remove();
            }
        }
    }

    private record Window(long startedAt, int count) {
    }
}
