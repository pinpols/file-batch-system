package io.github.pinpols.batch.worker.exports.region;

import io.github.pinpols.batch.common.exception.WorkerConfigException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 导出地区(region)解析(per-run + 默认回退 + 字典校验),与导入 {@code GenericJdbcMappedImportLoadPlugin#applyRegion}
 * 对称。
 *
 * <p>导出侧 region 语义与导入不同:导入把 region <b>写入</b>目标列(${region} binding),导出把 region 当作<b>读取过滤</b>条件
 * ({@code :region} 具名参数)+ 文件名占位({@code ${region}})。解析在 PrepareStep 统一完成,结果经 exportSnapshot 透传给
 * GENERATE 阶段的查询插件与文件名生成。
 *
 * <p>优先级:触发参数 {@code metadata.region} &gt; 模板 {@code defaultRegion} 回退。{@code allowedRegions}
 * 非空时做字典校验,非法地区直接 {@link WorkerConfigException} 拒绝(受控词表,非自由文本)。
 *
 * <p>地区配置取自模板 {@code query_param_schema} 下的导出插件块({@code sqlTemplateExport} / {@code
 * jdbcMappedExport},含 snake_case 别名),其次回退顶层 {@code default_region} / {@code allowed_regions}。
 */
public final class ExportRegionResolver {

  private ExportRegionResolver() {}

  /**
   * 解析 per-run 地区。
   *
   * @param templateConfig 模板配置(含 query_param_schema)
   * @param metadata 触发 payload 的 metadata(per-run 覆盖)
   * @return 规整后的 region(可能为 null:无触发值、无默认、且无字典)
   * @throws WorkerConfigException allowedRegions 非空且 region 不在其中
   */
  public static String resolve(Map<String, Object> templateConfig, Map<String, Object> metadata) {
    String triggerRegion = stringValue(metadata == null ? null : metadata.get("region"));
    Map<String, Object> regionCfg = regionConfig(templateConfig);
    String camelCaseDefaultRegion = stringValue(regionCfg.get("defaultRegion"));
    String snakeCaseDefaultRegion = stringValue(regionCfg.get("default_region"));
    String defaultRegion = firstNonBlank(camelCaseDefaultRegion, snakeCaseDefaultRegion);
    Object camelCaseAllowedRegions = regionCfg.get("allowedRegions");
    Object snakeCaseAllowedRegions = regionCfg.get("allowed_regions");
    List<String> allowed = stringList(camelCaseAllowedRegions, snakeCaseAllowedRegions);

    String region = Texts.hasText(triggerRegion) ? triggerRegion : defaultRegion;
    if (!EmptyChecks.isEmpty(Objects.requireNonNull(allowed))) {
      boolean regionMissing = EmptyChecks.isNull(region);
      boolean regionNotAllowed = !regionMissing && !allowed.contains(region);
      if (regionMissing || regionNotAllowed) {
        throw new WorkerConfigException(
            "export region not in allowedRegions: region=" + region + ", allowed=" + allowed);
      }
    }
    return region;
  }

  /**
   * 汇总地区配置来源:优先 query_param_schema 下的导出插件块(sqlTemplateExport / jdbcMappedExport,含 snake_case
   * 别名),其次顶层。后出现的来源不覆盖先出现的非空键(前者优先)。
   */
  private static Map<String, Object> regionConfig(Map<String, Object> templateConfig) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (templateConfig == null || templateConfig.isEmpty()) {
      return out;
    }
    Object qps = templateConfig.get("query_param_schema");
    if (qps instanceof Map<?, ?> schema) {
      mergeRegionKeys(out, asMap(schema.get("sqlTemplateExport")));
      mergeRegionKeys(out, asMap(schema.get("sql_template_export")));
      mergeRegionKeys(out, asMap(schema.get("jdbcMappedExport")));
      mergeRegionKeys(out, asMap(schema.get("jdbc_mapped_export")));
    }
    // 顶层回退(最低优先级)
    mergeRegionKeys(out, templateConfig);
    return out;
  }

  private static void mergeRegionKeys(Map<String, Object> out, Map<String, Object> src) {
    if (src == null) {
      return;
    }
    for (String key :
        new String[] {"defaultRegion", "default_region", "allowedRegions", "allowed_regions"}) {
      if (src.get(key) != null) {
        out.putIfAbsent(key, src.get(key));
      }
    }
  }

  private static Map<String, Object> asMap(Object raw) {
    if (raw instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, v) -> out.put(String.valueOf(k), v));
      return out;
    }
    return null;
  }

  private static String stringValue(Object v) {
    if (EmptyChecks.isNull(v)) {
      return null;
    }
    String valueText = String.valueOf(v);
    return valueText.trim();
  }

  private static String firstNonBlank(String a, String b) {
    return Texts.hasText(a) ? a : (Texts.hasText(b) ? b : null);
  }

  private static List<String> stringList(Object primary, Object alias) {
    Object raw = primary != null ? primary : alias;
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .filter(
            value -> {
              if (EmptyChecks.isNull(value)) {
                return false;
              }
              String filterText = String.valueOf(value);
              return Texts.hasText(filterText);
            })
        .map(
            value -> {
              String mappedText = String.valueOf(value);
              return mappedText.trim();
            })
        .toList();
  }
}
