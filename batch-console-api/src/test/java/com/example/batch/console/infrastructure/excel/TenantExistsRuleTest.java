package com.example.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TenantExistsRuleTest {

  @Test
  void shouldPass_whenAllReferencedTenantsExist() {
    List<WorkbookIssue> issues =
        TenantExistsRule.validate(Set.of("ta", "tb"), Set.of("ta", "tb", "tc", "default"));
    assertThat(issues).isEmpty();
  }

  @Test
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
  void shouldDedupReferenced_andSkipBlanks() {
    List<WorkbookIssue> issues =
        TenantExistsRule.validate(Arrays.asList("ta", "ta", "", "  ", null), Set.of());
    assertThat(issues).hasSize(1);
    assertThat(issues.get(0).message()).contains("tenant_id 'ta'");
  }

  @Test
  void shouldHandleEmpty() {
    assertThat(TenantExistsRule.validate(null, Set.of("ta"))).isEmpty();
    assertThat(TenantExistsRule.validate(List.of(), Set.of("ta"))).isEmpty();
  }

  @Test
  void shouldTreatNullExistingAsEmpty() {
    List<WorkbookIssue> issues = TenantExistsRule.validate(List.of("ta"), null);
    assertThat(issues).hasSize(1);
    assertThat(issues.get(0).message()).contains("'ta'");
  }
}
