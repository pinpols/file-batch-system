package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileTemplateSpec;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("租户初始化定长映射验证")
class FixedWidthTenantInitIntegrationTest extends AbstractIntegrationTest {
  @Autowired
  private ConsoleTenantConfigInitApplicationService init;

  @Autowired
  private JdbcTemplate jdbc;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("执行及演练均将无效定长映射计入失败且不落库")
  void shouldReportInvalidMapping_withoutPersistingIt(boolean dryRun) {
    String tenant = "fixed-init-" + Long.toUnsignedString(System.nanoTime());
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(tenant));
    request.setDryRun(dryRun);
    FileTemplateSpec spec = new FileTemplateSpec();
    spec.setTemplateCode("tpl_invalid");
    spec.setTemplateType("IMPORT");
    spec.setFileFormatType("FIXED_WIDTH");
    spec.setEnabled(true);
    spec.setFieldMappingsJson("[{\"target\":\"name\",\"start\":1.5,\"length\":2}]");
    request.setFileTemplates(List.of(spec));

    var result = init.batchInit(request, "test", "fixed-init-" + tenant);

    assertThat(result.results().getFirst().fileTemplates().failed()).isOne();
    assertThat(result.results().getFirst().fileTemplates().created()).isZero();
    String countSql =
        "select count(*) from batch.file_template_config where tenant_id = ? and template_code = ?";
    Long count = jdbc.queryForObject(countSql, Long.class, tenant, "tpl_invalid");
    assertThat(count).isZero();
  }
}
