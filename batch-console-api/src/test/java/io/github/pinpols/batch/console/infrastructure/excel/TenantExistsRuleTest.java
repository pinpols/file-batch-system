package io.github.pinpols.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("租户引用规则:配置包内租户存在性校验")
class TenantExistsRuleTest {

  @Test
  @DisplayName("租户引用全部存在:校验结果为空")
  void shouldPass_whenAllReferencedTenantsExist() {
    List<WorkbookIssue> issues =
        TenantExistsRule.validate(Set.of("ta", "tb"), Set.of("ta", "tb", "tc", "default"));
    assertThat(issues).isEmpty();
  }

  @Test
  @DisplayName("租户引用缺失:每个不存在的租户各上报一条问题,并附排查脚本提示")
  void shouldReport_eachMissingTenant() {
    List<WorkbookIssue> issues =
        TenantExistsRule.validate(List.of("ta", "tb", "tc"), Set.of("default"));

    assertThat(issues).hasSize(3);
    assertThat(issues)
        .extracting(WorkbookIssue::message)
        .anySatisfy(m -> assertThat(m).contains("tenant_id 'ta'"))
        .anySatisfy(m -> assertThat(m).contains("tenant_id 'tb'"))
        .anySatisfy(m -> assertThat(m).contains("tenant_id 'tc'"))
        .allSatisfy(m -> assertThat(m).contains("sim-e2e-bootstrap.sql"));
    assertThat(issues).extracting(WorkbookIssue::columnName).containsOnly("tenant_id");
  }

  @Test
  @DisplayName("租户引用去重:重复值与空白值只计一条问题")
  void shouldDedupReferenced_andSkipBlanks() {
    List<WorkbookIssue> issues =
        TenantExistsRule.validate(Arrays.asList("ta", "ta", "", "  ", null), Set.of());
    assertThat(issues).hasSize(1);
    assertThat(issues.get(0).message()).contains("tenant_id 'ta'");
  }

  @Test
  @DisplayName("空输入:引用集合或行集合为空时不产生问题")
  void shouldHandleEmpty() {
    assertThat(TenantExistsRule.validate(null, Set.of("ta"))).isEmpty();
    assertThat(TenantExistsRule.validate(List.of(), Set.of("ta"))).isEmpty();
  }

  @Test
  @DisplayName("已有租户集合为空:全部引用按缺失上报")
  void shouldTreatNullExistingAsEmpty() {
    List<WorkbookIssue> issues = TenantExistsRule.validate(List.of("ta"), null);
    assertThat(issues).hasSize(1);
    assertThat(issues.get(0).message()).contains("'ta'");
  }
}
