package com.example.batch.common.utils;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 全局唯一 ID 生成工具类。
 * {@code newTraceId()} 生成无连字符的 UUID 用于链路追踪；
 * {@code newBusinessNo(prefix)} 生成带前缀、ISO 时间戳和随机后缀的业务编号，格式为 {@code prefix-yyyyMMddTHHmmssZ-xxxxxxxx}。
 */
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
