package com.example.batch.worker.exports.stage.format;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 导出格式策略注册中心，收集所有 {@link ExportFormatStrategy} Bean 并按 {@link ExportFormatStrategy#formatType()}
 * 大小写不敏感索引。
 *
 * <p>与现有 {@code ExportDataPluginRegistry} 模式保持一致。
 */
@Component
public class ExportFormatStrategyRegistry {

  private final Map<String, ExportFormatStrategy> strategiesByType;

  public ExportFormatStrategyRegistry(List<ExportFormatStrategy> strategies) {
    this.strategiesByType =
        strategies.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    s -> s.formatType().toUpperCase(), Function.identity()));
  }

  /**
   * 按格式类型查找策略，格式类型为空或未注册时回退到 {@code "JSON"}。
   *
   * @param fileFormatType 格式类型标识
   * @return 对应的策略实现
   */
  public ExportFormatStrategy resolve(String fileFormatType) {
    if (fileFormatType == null || fileFormatType.isBlank()) {
      return require("JSON");
    }
    ExportFormatStrategy strategy = strategiesByType.get(fileFormatType.trim().toUpperCase());
    return strategy != null ? strategy : require("JSON");
  }

  /**
   * 按格式类型获取策略，未注册时抛出异常。
   *
   * @param fileFormatType 格式类型标识
   * @return 对应的策略实现
   * @throws com.example.batch.common.exception.BizException 未找到策略时抛出
   */
  public ExportFormatStrategy require(String fileFormatType) {
    ExportFormatStrategy strategy =
        strategiesByType.get(fileFormatType == null ? "" : fileFormatType.trim().toUpperCase());
    if (strategy == null) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.export.format_not_supported", fileFormatType);
    }
    return strategy;
  }
}
