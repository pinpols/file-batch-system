package com.example.batch.common.utils;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public final class IdGenerator {

  private IdGenerator() {}

  public static String newTraceId() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  public static String newBusinessNo(String prefix) {
    return prefix
        + "-"
        + DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "").replace("-", "")
        + "-"
        + UUID.randomUUID().toString().substring(0, 8);
  }
}
