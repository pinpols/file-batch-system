package com.example.batch.console.infrastructure.realtime;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class ConsoleRealtimeCursorFactory {

    public String nextCursor() {
        return Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
    }
}
