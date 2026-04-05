package com.example.batch.console.infrastructure.realtime;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ConsoleRealtimeCursorFactory {

    public String nextCursor() {
        return Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
    }
}
