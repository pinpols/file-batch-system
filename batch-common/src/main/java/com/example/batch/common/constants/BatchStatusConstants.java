package com.example.batch.common.constants;

/** 批处理生命周期状态字面量集中地;改动需同步 BatchLifecycleStatus 枚举与 DB CHECK 约束。 */
public final class BatchStatusConstants {

  public static final String ACCEPTED = "ACCEPTED";
  public static final String DUPLICATE = "DUPLICATE";
  public static final String REJECTED = "REJECTED";
  public static final String LAUNCHED = "LAUNCHED";
  public static final String CREATED = "CREATED";
  public static final String WAITING = "WAITING";
  public static final String READY = "READY";
  public static final String RUNNING = "RUNNING";
  public static final String SUCCESS = "SUCCESS";
  public static final String FAILED = "FAILED";
  public static final String PARTIAL_FAILED = "PARTIAL_FAILED";
  public static final String START = "START";
  public static final String END = "END";

  private BatchStatusConstants() {}
}
