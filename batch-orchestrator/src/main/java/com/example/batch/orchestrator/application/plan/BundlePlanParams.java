package com.example.batch.orchestrator.application.plan;

import com.example.batch.common.enums.JobType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ADR-046 文件束:从 launch params 解析「这束有哪些绑定项」。
 *
 * <p>约定 params 携带 {@code bundleFiles} = {@code List<Map>},每项 {@code {sourceFileId, templateCode,
 * targetRef?}}——由触发链查 file_record 后填入。launch 据此把一束展成 N 个异构 partition。{@link
 * BundlePartitionCountResolver} 与 {@link DefaultSchedulePlanBuilder} 共用本解析,保持单一来源。
 *
 * <p><b>承重字段按束类型不同(绑定 profile)</b>:
 *
 * <ul>
 *   <li>{@code BUNDLE_IMPORT}:{@code sourceFileId}(源文件)+ {@code templateCode}(导入模板)。
 *   <li>{@code BUNDLE_EXPORT}:{@code templateCode}(导出模板=源表/查询);导出无源文件,{@code sourceFileId} 为空。
 *   <li>{@code BUNDLE_DISPATCH}:{@code sourceFileId}(待分发文件)+ {@code targetRef}(下游渠道);分发无模板。
 * </ul>
 *
 * 缺承重字段的项跳过(不静默落一个无绑定 partition)。
 */
public final class BundlePlanParams {

  /** launch params 里承载文件束清单的键。 */
  public static final String PARAM_BUNDLE_FILES = "bundleFiles";

  private BundlePlanParams() {}

  /** 一束内单个绑定项(文件/模板/目标视类型而定,可部分为空)。 */
  public record BundleFile(Long sourceFileId, String templateCode, String targetRef) {}

  /** 解析 params.bundleFiles。承重字段校验按束类型(绑定 profile)区分;非束类型或承重缺失 → 跳过/空列表(走原同构路径)。 */
  @SuppressWarnings("unchecked")
  public static List<BundleFile> extract(Map<String, Object> params, JobType bundleType) {
    List<BundleFile> result = new ArrayList<>();
    if (params == null || bundleType == null || !bundleType.isBundle()) {
      return result;
    }
    Object raw = params.get(PARAM_BUNDLE_FILES);
    if (!(raw instanceof List<?> list)) {
      return result;
    }
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) {
        continue;
      }
      Map<String, Object> entry = (Map<String, Object>) map;
      Long fileId = toLong(entry.get("sourceFileId"));
      String templateCode = toStr(entry.get("templateCode"));
      String targetRef = toStr(entry.get("targetRef"));
      if (!hasRequiredBinding(bundleType, fileId, templateCode, targetRef)) {
        continue;
      }
      result.add(new BundleFile(fileId, templateCode, targetRef));
    }
    return result;
  }

  /** 按绑定 profile 校验承重字段是否齐备。 */
  private static boolean hasRequiredBinding(
      JobType bundleType, Long fileId, String templateCode, String targetRef) {
    return switch (bundleType) {
      case BUNDLE_IMPORT -> fileId != null && templateCode != null;
      case BUNDLE_EXPORT -> templateCode != null;
      case BUNDLE_DISPATCH -> fileId != null && targetRef != null;
      default -> false;
    };
  }

  private static Long toLong(Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    if (value == null) {
      return null;
    }
    try {
      return Long.parseLong(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static String toStr(Object value) {
    if (value == null) {
      return null;
    }
    String s = String.valueOf(value).trim();
    return s.isEmpty() ? null : s;
  }
}
