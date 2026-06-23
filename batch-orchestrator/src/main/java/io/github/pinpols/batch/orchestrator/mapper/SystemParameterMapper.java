package io.github.pinpols.batch.orchestrator.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * orchestrator 侧对 {@code batch.system_parameter}(租户级键值配置)的轻量只读访问。
 *
 * <p>该表的 CRUD 由 console {@code ConsoleSystemParameterController} 负责;orchestrator 只读取单个 param_value
 * 用于 register 准入判定(如 {@code worker.min_sdk_version} 最低 SDK 版本门禁)。
 */
public interface SystemParameterMapper {

  /**
   * 按 (tenantId, paramKey) 读单个 param_value;未配置返 {@code null}(调用方据此 opt-in 放行)。
   *
   * @param tenantId 租户 id(非空,租户维度过滤)
   * @param paramKey 参数键(如 {@code "worker.min_sdk_version"})
   * @return param_value 字符串;未命中返 null
   */
  String selectParamValue(@Param("tenantId") String tenantId, @Param("paramKey") String paramKey);
}
