package com.example.batch.common.event;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 域事件不可变描述。**所有**业务想发到 outbox 的事件统一通过本对象表达,各模块的 publisher 实现负责把它写入对应的 outbox 表 (orchestrator →
 * outbox_event;trigger → trigger_outbox_event)。
 *
 * <p>动机:统一前 30+ 处手写 OutboxEventEntity 的 builder 散落各业务 service,字段填漏(eventKey
 * 不一致导致幂等失效)、命名漂移(aggregateType 同义不同写)、payload 序列化 JSON 各处自己 toJson。
 *
 * <p>构造用 {@link #builder(String)} 起步,链式赋值后 build。
 */
public record DomainEvent(
    String tenantId,
    String aggregateType,
    Long aggregateId,
    String eventType,
    String eventKey,
    Map<String, Object> payload,
    String traceId,
    String schemaVersion,
    Integer priority) {

  public DomainEvent {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("DomainEvent.tenantId must not be blank");
    }
    if (aggregateType == null || aggregateType.isBlank()) {
      throw new IllegalArgumentException("DomainEvent.aggregateType must not be blank");
    }
    if (eventType == null || eventType.isBlank()) {
      throw new IllegalArgumentException("DomainEvent.eventType must not be blank");
    }
    // Map.copyOf 不允许 null 值,但 outbox payload 允许 null(JSON 序列化为 null,业务依赖此语义)
    payload =
        payload == null
            ? java.util.Map.of()
            : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    schemaVersion = schemaVersion == null ? "v1" : schemaVersion;
  }

  public static Builder builder(String tenantId) {
    return new Builder(tenantId);
  }

  public static final class Builder {
    private final String tenantId;
    private String aggregateType;
    private Long aggregateId;
    private String eventType;
    private String eventKey;
    private Map<String, Object> payload = new LinkedHashMap<>();
    private String traceId;
    private String schemaVersion = "v1";
    private Integer priority;

    private Builder(String tenantId) {
      this.tenantId = tenantId;
    }

    public Builder aggregate(String type, Long id) {
      this.aggregateType = type;
      this.aggregateId = id;
      return this;
    }

    public Builder type(String eventType) {
      this.eventType = eventType;
      return this;
    }

    public Builder key(String eventKey) {
      this.eventKey = eventKey;
      return this;
    }

    public Builder payload(Map<String, Object> payload) {
      this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
      return this;
    }

    public Builder payloadEntry(String key, Object value) {
      this.payload.put(key, value);
      return this;
    }

    public Builder traceId(String traceId) {
      this.traceId = traceId;
      return this;
    }

    public Builder schemaVersion(String version) {
      this.schemaVersion = version;
      return this;
    }

    /** outbox 派发优先级(可选);只 orchestrator outbox_event 表用,默认走 DB DEFAULT 5。 */
    public Builder priority(Integer priority) {
      this.priority = priority;
      return this;
    }

    public DomainEvent build() {
      return new DomainEvent(
          tenantId,
          aggregateType,
          aggregateId,
          eventType,
          eventKey,
          payload,
          traceId,
          schemaVersion,
          priority);
    }
  }
}
