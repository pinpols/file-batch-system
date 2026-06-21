package com.example.batch.worker.imports.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Optional;

/**
 * 批次清单。
 *
 * <ul>
 *   <li><b>v1</b>(ADR-040 {@code batch-manifest-v1}):上游声明当天某组的预期文件集合({@link #requiredFiles})。
 *   <li><b>v2</b>(ADR-046 文件束聚合):在 v1 之上**可选**追加 {@link #fileMapping}——逐文件声明用哪个模板 ({@code
 *       templateCode}),供束作业(BUNDLE_IMPORT)把一束文件展成异构 partition(各落各自的表)。目标表默认从 模板的 {@code
 *       query_param_schema.jdbcMappedImport.table} 推导,仅需覆盖时才显式给 {@code targetTable}。
 * </ul>
 *
 * <p>向后兼容:{@code fileMapping} 缺省为 null,旧 v1 清单零改照跑;未知字段忽略。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchManifest(
    String schemaVersion,
    String fileGroupCode,
    String bizDate,
    String tenantId,
    List<String> requiredFiles,
    List<FileMapping> fileMapping,
    String generatedAt) {

  /** v2 逐文件映射:文件名 → 模板 code(+ 可选目标表覆盖)。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FileMapping(String fileName, String templateCode, String targetTable) {}

  /** 是否带 v2 的逐文件映射(束作业据此判断走异构展开)。 */
  public boolean hasFileMapping() {
    return fileMapping != null && !fileMapping.isEmpty();
  }

  /** 查某文件声明的模板 code;无映射 / 未声明 / 入参为空时返回 empty。 */
  public Optional<String> templateCodeFor(String fileName) {
    if (fileMapping == null || fileName == null) {
      return Optional.empty();
    }
    return fileMapping.stream()
        .filter(m -> m != null && fileName.equals(m.fileName()) && m.templateCode() != null)
        .map(FileMapping::templateCode)
        .findFirst();
  }
}
