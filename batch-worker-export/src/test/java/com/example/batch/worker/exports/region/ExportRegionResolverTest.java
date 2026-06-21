package com.example.batch.worker.exports.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.exception.WorkerConfigException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 导出地区解析:per-run 优先 / 模板默认回退 / allowedRegions 字典校验 / 顶层 + snake_case 别名。 */
class ExportRegionResolverTest {

  /** query_param_schema.sqlTemplateExport 下配 defaultRegion(可空) + allowedRegions。 */
  private static Map<String, Object> templateWithRegionCfg(
      String defaultRegion, List<String> allowed) {
    Map<String, Object> cfg = new LinkedHashMap<>();
    if (defaultRegion != null) {
      cfg.put("defaultRegion", defaultRegion);
    }
    cfg.put("allowedRegions", allowed);
    return Map.of("query_param_schema", Map.of("sqlTemplateExport", cfg));
  }

  @Test
  void keepsTriggerRegionWhenAllowed() {
    String region =
        ExportRegionResolver.resolve(
            templateWithRegionCfg("BJ", List.of("BJ", "SH", "GD")), Map.of("region", "GD"));
    assertThat(region).isEqualTo("GD");
  }

  @Test
  void fallsBackToTemplateDefaultWhenTriggerMissing() {
    String region =
        ExportRegionResolver.resolve(templateWithRegionCfg("SH", List.of("BJ", "SH")), Map.of());
    assertThat(region).isEqualTo("SH");
  }

  @Test
  void rejectsRegionNotInDictionary() {
    assertThatThrownBy(
            () ->
                ExportRegionResolver.resolve(
                    templateWithRegionCfg(null, List.of("BJ", "SH")), Map.of("region", "XX")))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("allowedRegions");
  }

  @Test
  void rejectsWhenNoRegionResolvedButDictionaryDeclared() {
    // 字典声明但既无触发值也无默认 → region=null 不在词表 → 拒绝
    assertThatThrownBy(
            () ->
                ExportRegionResolver.resolve(
                    templateWithRegionCfg(null, List.of("BJ", "SH")), Map.of()))
        .isInstanceOf(WorkerConfigException.class);
  }

  @Test
  void returnsNullWhenNoConfigAndNoTrigger() {
    assertThat(ExportRegionResolver.resolve(Map.of(), Map.of())).isNull();
    assertThat(ExportRegionResolver.resolve(null, null)).isNull();
  }

  @Test
  void noValidationWhenDictionaryEmpty() {
    String region =
        ExportRegionResolver.resolve(
            templateWithRegionCfg(null, List.of()), Map.of("region", "GD"));
    assertThat(region).isEqualTo("GD");
  }

  @Test
  void readsSnakeCaseAliasesAndTopLevelFallback() {
    // 顶层 default_region / allowed_regions(snake_case 别名)回退
    Map<String, Object> tpl =
        Map.of("default_region", "SH", "allowed_regions", List.of("BJ", "SH"));
    assertThat(ExportRegionResolver.resolve(tpl, Map.of())).isEqualTo("SH");
  }

  @Test
  void triggerOverridesDefaultButStillDictChecked() {
    // 触发值覆盖默认,但仍走字典:触发非法 → 拒绝
    assertThatThrownBy(
            () ->
                ExportRegionResolver.resolve(
                    templateWithRegionCfg("BJ", List.of("BJ", "SH")), Map.of("region", "ZZ")))
        .isInstanceOf(WorkerConfigException.class);
  }
}
