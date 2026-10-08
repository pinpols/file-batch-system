package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest.ConfigType;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigMatrixRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigMatrixResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("租户配置矩阵构建器:基线与差异字段")
class TenantConfigMatrixBuilderTest {

  @Test
  @DisplayName("默认以首个租户为基线并标记其他租户的配置差异")
  void usesFirstTenantAsBaseline_whenBaselineTenantOmitted() {
    TenantConfigMatrixRequest request = new TenantConfigMatrixRequest();
    request.setTenantIds(List.of("tenant-a", "tenant-b"));
    request.setJobCodes(List.of("JOB_A"));
    ConfigSyncBundlePayload baseline = bundle("DAILY");
    ConfigSyncBundlePayload different = bundle("HOURLY");

    TenantConfigMatrixResponse response = TenantConfigMatrixBuilder.build(
        request,
        (tenantId, configTypes) -> {
          assertThat(configTypes)
              .containsExactlyInAnyOrder(
                  ConfigType.JOB_DEFINITION,
                  ConfigType.WORKFLOW_DEFINITION,
                  ConfigType.PIPELINE_DEFINITION,
                  ConfigType.FILE_CHANNEL,
                  ConfigType.FILE_TEMPLATE,
                  ConfigType.RESOURCE_QUEUE,
                  ConfigType.BATCH_WINDOW,
                  ConfigType.BUSINESS_CALENDAR);
          return "tenant-a".equals(tenantId) ? baseline : different;
        },
        new TenantConfigReferenceResolver());

    assertThat(response.baselineTenantId()).isEqualTo("tenant-a");
    assertThat(response.rows()).hasSize(2);
    assertThat(response.rows().get(0).driftFields()).isEmpty();
    assertThat(response.rows().get(1).driftFields()).containsExactly("scheduleType");
  }

  @Test
  @DisplayName("请求指定基线租户时按该租户计算差异")
  void usesRequestedBaselineTenant_whenTenantProvided() {
    TenantConfigMatrixRequest request = new TenantConfigMatrixRequest();
    request.setTenantIds(List.of("tenant-a", "tenant-b"));
    request.setJobCodes(List.of("JOB_A"));
    request.setBaselineTenantId("tenant-b");

    TenantConfigMatrixResponse response = TenantConfigMatrixBuilder.build(
        request,
        (tenantId, configTypes) -> bundle("tenant-b".equals(tenantId) ? "DAILY" : "HOURLY"),
        new TenantConfigReferenceResolver());

    assertThat(response.baselineTenantId()).isEqualTo("tenant-b");
    assertThat(response.rows().get(0).driftFields()).containsExactly("scheduleType");
    assertThat(response.rows().get(1).driftFields()).isEmpty();
  }

  private static ConfigSyncBundlePayload bundle(String scheduleType) {
    JobDefinitionSpec job = new JobDefinitionSpec();
    job.setJobCode("JOB_A");
    job.setScheduleType(scheduleType);
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    bundle.setJobDefinitions(List.of(job));
    return bundle;
  }
}
