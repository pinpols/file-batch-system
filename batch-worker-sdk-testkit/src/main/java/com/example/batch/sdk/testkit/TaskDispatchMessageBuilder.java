package com.example.batch.sdk.testkit;

import com.example.batch.sdk.dispatcher.TaskDispatchMessage;
import com.example.batch.sdk.task.SdkSchedulingContext;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试夹具 — 构造 {@link TaskDispatchMessage}(record 本身无 builder),给 {@link FakeBatchPlatform#dispatch} 用。
 *
 * <p>只 {@code taskType} 必填,其余给测试友好默认:{@code taskId} 进程内自增、{@code tenantId="testkit-tenant"}、 {@code
 * schemaVersion="v1"}。典型用法:
 *
 * <pre>{@code
 * TaskDispatchMessage msg = TaskDispatchMessageBuilder.dispatch("my_import")
 *     .tenantId("t1")
 *     .param("path", "/data/in.csv")
 *     .build();
 * platform.dispatch(msg);
 * }</pre>
 */
public final class TaskDispatchMessageBuilder {

  private static final AtomicLong TASK_ID_SEQ = new AtomicLong(1);

  private String schemaVersion = TaskDispatchMessage.DEFAULT_SCHEMA_VERSION;
  private Long taskId;
  private String tenantId = "testkit-tenant";
  private String jobCode = "testkit-job";
  private final String taskType;
  private String taskInstanceId;
  private final Map<String, Object> parameters = new HashMap<>();
  private final Map<String, Object> runtimeAttributes = new HashMap<>();
  private SdkSchedulingContext schedulingContext;

  private TaskDispatchMessageBuilder(String taskType) {
    if (taskType == null || taskType.isBlank()) {
      throw new IllegalArgumentException("taskType must be non-blank");
    }
    this.taskType = taskType;
  }

  /** 起一个派单消息构造器(taskType 即 SDK handler 路由键)。 */
  public static TaskDispatchMessageBuilder dispatch(String taskType) {
    return new TaskDispatchMessageBuilder(taskType);
  }

  public TaskDispatchMessageBuilder schemaVersion(String value) {
    this.schemaVersion = value;
    return this;
  }

  public TaskDispatchMessageBuilder taskId(Long value) {
    this.taskId = value;
    return this;
  }

  public TaskDispatchMessageBuilder tenantId(String value) {
    this.tenantId = value;
    return this;
  }

  public TaskDispatchMessageBuilder jobCode(String value) {
    this.jobCode = value;
    return this;
  }

  public TaskDispatchMessageBuilder taskInstanceId(String value) {
    this.taskInstanceId = value;
    return this;
  }

  public TaskDispatchMessageBuilder parameters(Map<String, Object> values) {
    this.parameters.clear();
    if (values != null) {
      this.parameters.putAll(values);
    }
    return this;
  }

  public TaskDispatchMessageBuilder param(String key, Object value) {
    this.parameters.put(key, value);
    return this;
  }

  public TaskDispatchMessageBuilder runtimeAttribute(String key, Object value) {
    this.runtimeAttributes.put(key, value);
    return this;
  }

  public TaskDispatchMessageBuilder schedulingContext(SdkSchedulingContext value) {
    this.schedulingContext = value;
    return this;
  }

  public TaskDispatchMessage build() {
    long resolvedTaskId = taskId != null ? taskId : TASK_ID_SEQ.getAndIncrement();
    String resolvedInstanceId = taskInstanceId != null ? taskInstanceId : "ti-" + resolvedTaskId;
    return new TaskDispatchMessage(
        schemaVersion,
        resolvedTaskId,
        tenantId,
        jobCode,
        taskType,
        resolvedInstanceId,
        Map.copyOf(parameters),
        Map.copyOf(runtimeAttributes),
        schedulingContext);
  }
}
