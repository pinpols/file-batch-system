package com.example.batch.orchestrator.application.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ADR-046 文件束:从 launch params 解析「这束有哪些文件 + 各自模板」。
 *
 * <p>约定 params 携带 {@code bundleFiles} = {@code List<Map>},每项 {@code {sourceFileId, templateCode,
 * targetRef?}}——由 2c-2 触发链(到达组凑齐)查 file_record 后填入。launch 据此把一束 展成 N 个异构
 * partition(各绑各自文件/模板)。{@link BundlePartitionCountResolver} 与 {@link DefaultSchedulePlanBuilder}
 * 共用本解析,保持单一来源。
 */
public final class BundlePlanParams {

  /** launch params 里承载文件束清单的键。 */
  public static final String PARAM_BUNDLE_FILES = "bundleFiles";

  private BundlePlanParams() {}

  /** 一束内单个文件的绑定。 */
  public record BundleFile(Long sourceFileId, String templateCode, String targetRef) {}

  /** 解析 params.bundleFiles 为类型化列表;无 / 非法 → 空列表(非束作业或未填,走原同构路径)。 */
  @SuppressWarnings("unchecked")
  public static List<BundleFile> extract(Map<String, Object> params) {
    List<BundleFile> result = new ArrayList<>();
    if (params == null) {
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
      if (fileId == null || templateCode == null) {
        // 文件 id + 模板是束展开的承重信息,缺则跳过该项(不静默落一个无绑定 partition)
        continue;
      }
      result.add(new BundleFile(fileId, templateCode, toStr(entry.get("targetRef"))));
    }
    return result;
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
