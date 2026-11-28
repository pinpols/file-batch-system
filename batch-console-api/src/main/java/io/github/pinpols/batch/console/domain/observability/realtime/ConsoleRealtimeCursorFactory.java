package io.github.pinpols.batch.console.domain.observability.realtime;

import java.util.UUID;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;

@Component
@RequiredArgsConstructor
public class ConsoleRealtimeCursorFactory {

    private final BatchDateTimeSupport dateTimeSupport;

    public String nextCursor() {
        return dateTimeSupport.currentEpochMillis() + "-" + UUID.randomUUID();
    }
}
