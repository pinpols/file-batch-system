package io.github.pinpols.batch.console.domain.observability.realtime;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConsoleRealtimeCursorFactory {

  private final BatchDateTimeSupport dateTimeSupport;

  public String nextCursor() {
    return dateTimeSupport.currentEpochMillis() + "-" + UUID.randomUUID();
  }
}
