package io.github.pinpols.batch.worker.exports.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.WorkerConfigException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 导出地区解析:per-run 优先 / 模板默认回退 / allowedRegions 字典校验 / 顶层 + snake_case 别名。 */
@DisplayName("导出地区解析单测:触发值优先,模板默认回退与允许字典校验语义")
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
  @DisplayName("触发带入的地区在允许字典内时,原样保留")
  void shouldKeepTriggerRegion_whenRegionIsAllowed() {
    String region = ExportRegionResolver.resolve(
        templateWithRegionCfg("BJ", List.of("BJ", "SH", "GD")), Map.of("region", "GD"));
    assertThat(region).isEqualTo("GD");
  }

  @Test
  @DisplayName("触发未提供地区时,回退到模板默认地区")
  void shouldFallBackToTemplateDefault_whenTriggerRegionMissing() {
    String region =
        ExportRegionResolver.resolve(templateWithRegionCfg("SH", List.of("BJ", "SH")), Map.of());
    assertThat(region).isEqualTo("SH");
  }

  @Test
  @DisplayName("地区不在允许字典内时抛配置异常,并提示允许列表")
  void shouldRejectRegion_whenNotInDictionary() {
    assertThatThrownBy(() -> ExportRegionResolver.resolve(
            templateWithRegionCfg(null, List.of("BJ", "SH")), Map.of("region", "XX")))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("allowedRegions");
  }

  @Test
  @DisplayName("声明了允许字典却无法解析出地区时,直接拒绝")
  void shouldReject_whenNoRegionResolvedButDictionaryDeclared() {
    // 字典声明但既无触发值也无默认 → region=null 不在词表 → 拒绝
    assertThatThrownBy(() -> ExportRegionResolver.resolve(
            templateWithRegionCfg(null, List.of("BJ", "SH")), Map.of()))
        .isInstanceOf(WorkerConfigException.class);
  }

  @Test
  @DisplayName("既无模板配置也无触发值时,地区解析为空")
  void shouldReturnNull_whenNoConfigAndNoTrigger() {
    assertThat(ExportRegionResolver.resolve(Map.of(), Map.of())).isNull();
    assertThat(ExportRegionResolver.resolve(null, null)).isNull();
  }

  @Test
  @DisplayName("允许字典为空时不做校验,触发地区直接生效")
  void shouldSkipDictionaryCheck_whenAllowedRegionsEmpty() {
    String region = ExportRegionResolver.resolve(
        templateWithRegionCfg(null, List.of()), Map.of("region", "GD"));
    assertThat(region).isEqualTo("GD");
  }

  @Test
  @DisplayName("顶层下划线写法的别名同样能解析出默认地区")
  void shouldReadSnakeCaseAliases_whenTopLevelConfigGiven() {
    // 顶层 default_region / allowed_regions(snake_case 别名)回退
    Map<String, Object> tpl =
        Map.of("default_region", "SH", "allowed_regions", List.of("BJ", "SH"));
    assertThat(ExportRegionResolver.resolve(tpl, Map.of())).isEqualTo("SH");
  }

  @Test
  @DisplayName("触发值覆盖默认值,但仍需过字典校验,越界即拒绝")
  void shouldRejectTriggerRegion_whenOutsideDictionaryEvenIfDefaultExists() {
    // 触发值覆盖默认,但仍走字典:触发非法 → 拒绝
    assertThatThrownBy(() -> ExportRegionResolver.resolve(
            templateWithRegionCfg("BJ", List.of("BJ", "SH")), Map.of("region", "ZZ")))
        .isInstanceOf(WorkerConfigException.class);
  }
}
