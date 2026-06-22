package com.example.batch.worker.processes.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 加工任务的消息负载，由 Kafka 消息反序列化而来。
 *
 * <p>{@code ignoreUnknown=true}：orch 派发消息携带的字段集随业务迭代会扩，worker 侧不应因未知字段直接抛
 * UnrecognizedPropertyException；未识别字段安全丢弃即可，与 {@code ImportPayload} / {@code ExportPayload} 一致。
 *
 * <p>PROCESS 业务输入比 IMPORT / EXPORT 更动态（最终读 SQL 模板的命名参数），固定字段只列三个常用控制字段，其余通过 {@code metadata} 透传给
 * plugin 作为命名参数源。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProcessPayload(
    String bizDate,
    String batchKey,
    String processImplCode,
    @JsonProperty("run_mode") @JsonAlias("runMode") String runMode,
    Map<String, Object> metadata) {}
