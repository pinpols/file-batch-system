package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.console.domain.entity.ConfigReleaseEntity;

/** 校验版本化配置发布单，并将其应用到运行时配置表。 */
public interface ConfigReleaseApplyService {

  /** 返回用于版本分配和锁定的规范化持久化类型。 */
  String canonicalType(String configType);

  /** 校验配置载荷契约，并确认载荷标识与发布键一致。 */
  void validate(String configType, String configKey, String configPayloadJson);

  /** 原子应用一条发布单；外围发布状态事务由调用方负责。 */
  void apply(ConfigReleaseEntity release, String operatorId, String operationId);
}
