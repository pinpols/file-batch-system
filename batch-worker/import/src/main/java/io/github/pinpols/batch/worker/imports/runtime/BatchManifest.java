package io.github.pinpols.batch.worker.imports.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 批次清单。
 *
 * <ul>
 *   <li><b>v1</b>(ADR-040 {@code batch-manifest-v1}):上游声明当天某组的预期文件集合({@link #requiredFiles})。
 *   <li><b>v2</b>(ADR-046 文件束聚合):在 v1 之上**可选**追加两项——① {@link #fileMapping}:逐文件声明绑定, <b>导入束</b>给
 *       {@code templateCode}(目标表默认从模板的 {@code query_param_schema.jdbcMappedImport.table}
 *       推导,仅需覆盖时才显式给 {@code targetTable}), <b>分发束</b>给 {@code targetRef}(下游渠道 {@code
 *       channel_code}),供束作业把一束文件展成异构 partition;② {@link #workflowCode}:本束满足条件后该启动的 Workflow
 *       code(上游声明「这束 → 这个 Workflow」),由 scanner 落到 file_record,到达组满足条件时据此发起根运行。
 * </ul>
 *
 * <p>当前根目标只接受 {@code workflowCode}。{@code jobCode} 字段只用于识别并拒绝旧根目标清单，不参与解析或回退。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchManifest(
    String schemaVersion,
    String fileGroupCode,
    String bizDate,
    String tenantId,
    List<String> requiredFiles,
    List<FileMapping> fileMapping,
    String workflowCode,
    String jobCode,
    String generatedAt) {

  public boolean hasRetiredJobTarget() {
    return EmptyChecks.isNotNull(jobCode) && EmptyChecks.isNotBlank(jobCode);
  }

  /**
   * v2 逐文件映射:文件名 → 绑定。导入束用 {@code templateCode}(+ 可选 {@code targetTable} 覆盖);分发束用 {@code
   * targetRef}(下游渠道 channel_code)。两者按束类型二选一,未用的留 null。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FileMapping(
      String fileName, String templateCode, String targetTable, String targetRef) {}

  /** 是否带 v2 的逐文件映射(束作业据此判断走异构展开)。 */
  public boolean hasFileMapping() {
    return fileMapping != null && !fileMapping.isEmpty();
  }

  /** 查某文件声明的模板 code(导入束);无映射 / 未声明 / 入参为空时返回 empty。 */
  public Optional<String> templateCodeFor(String fileName) {
    return mappingValueFor(fileName, FileMapping::templateCode);
  }

  /** 查某文件声明的下游渠道 targetRef(分发束);无映射 / 未声明 / 入参为空时返回 empty。 */
  public Optional<String> targetRefFor(String fileName) {
    return mappingValueFor(fileName, FileMapping::targetRef);
  }

  private Optional<String> mappingValueFor(
      String fileName, Function<FileMapping, String> extractor) {
    if (fileMapping == null || fileName == null) {
      return Optional.empty();
    }
    Stream<FileMapping> fileNameMatches = fileMapping.stream()
        .filter(mapping -> EmptyChecks.isNotNull(mapping) && fileName.equals(mapping.fileName()));
    return fileNameMatches
        .filter(mapping -> EmptyChecks.isNotNull(extractor.apply(mapping)))
        .map(extractor)
        .findFirst();
  }

  /**
   * 是否「manifest-only 导出束」清单(ADR-046 Phase3 导出触发):有 {@link #workflowCode}(BUNDLE_EXPORT Workflow)+ fileMapping
   * 声明 ≥1 个导出模板(templateCode)+ <b>无 {@link #requiredFiles}</b>(导出无输入数据文件,清单本身即触发,
   * 不等文件到达)。导入/分发束清单必有 requiredFiles(数据文件),故不会命中。
   */
  public boolean isManifestOnlyExport() {
    if (EmptyChecks.isBlank(workflowCode)) {
      return false;
    }
    if (hasRetiredJobTarget()) {
      return false;
    }
    boolean hasNoRequiredInputFiles = EmptyChecks.isNull(requiredFiles)
        || EmptyChecks.isEmpty(Objects.requireNonNull(requiredFiles));
    if (!hasNoRequiredInputFiles) {
      return false;
    }
    List<String> declaredExportTemplateCodes = exportTemplateCodes();
    return !EmptyChecks.isEmpty(Objects.requireNonNull(declaredExportTemplateCodes));
  }

  /** 导出束清单声明的导出模板 code 列表(fileMapping 里非空 templateCode,按声明顺序);无则空表。 */
  public List<String> exportTemplateCodes() {
    if (fileMapping == null) {
      return List.of();
    }
    return fileMapping.stream()
        .filter(m -> m != null && m.templateCode() != null && !m.templateCode().isBlank())
        .map(FileMapping::templateCode)
        .toList();
  }
}
