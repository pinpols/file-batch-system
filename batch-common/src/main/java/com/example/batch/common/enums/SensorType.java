package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * ADR-028 Sensor WAIT 节点的传感器类型。
 *
 * <p>每种 sensor 对应 {@code node_params.sensor_spec} 的不同 schema：
 *
 * <ul>
 *   <li>FILE_ARRIVAL — 等待文件落到指定 channel/路径；spec = {channelCode, pattern, maxAgeSeconds}
 *   <li>HTTP_POLL — 轮询外部 URL；spec = {url, method, headersJson, matchExpr}
 *   <li>KAFKA_OFFSET — 等待 topic 某 partition 消费位点；spec = {topic, partition, minOffset}
 *   <li>DB_ROW_EXISTS — 业务库 select 命中行；spec = {schema, sql} （SQL 必须 readonly + schema 白名单）
 * </ul>
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum SensorType implements DictEnum {
  FILE_ARRIVAL("FILE_ARRIVAL", "等待文件到达"),
  HTTP_POLL("HTTP_POLL", "HTTP 轮询"),
  KAFKA_OFFSET("KAFKA_OFFSET", "Kafka 位点"),
  DB_ROW_EXISTS("DB_ROW_EXISTS", "数据库行存在");

  private final String code;
  private final String label;
}
