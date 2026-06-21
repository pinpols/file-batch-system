package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-046 文件束:控制面集成测试——验证 {@link LaunchService#launch} 对束作业按 {@code params.bundleFiles} 展开成 N 个
 * <b>异构、各自绑定</b>的 {@code job_partition}(真 PG)。这是 unit 测(mock configCache 的
 * DefaultSchedulePlanBuilderTest)触不到的那段:真实 DB 上的 resolver 链 + partition 落库 + 绑定列写入。
 *
 * <p>三类束的绑定 profile 各异:import=源文件+模板、export=模板(无源文件)、dispatch=源文件+下游渠道。 不起 worker(worker 执行侧已被
 * ReceiveStep/DispatchPayload/ExportPayload 单测覆盖;全链真跑见 sim bundle stage)。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BundleLaunchPartitionExpansionIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

  @Autowired private LaunchService launchService;
  @Autowired private JobInstanceMapper jobInstanceMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private List<Map<String, Object>> launchBundleAndReadPartitions(
      String bundleJobType, String workerGroup, List<Map<String, Object>> bundleFiles) {
    LaunchSeed seed =
        LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
            jdbcTemplate, TENANT, bundleJobType, workerGroup);

    LaunchRequest request =
        LaunchRequest.builder()
            .tenantId(TENANT)
            .jobCode(seed.jobCode())
            .bizDate(BIZ_DATE)
            .triggerType(TriggerType.EVENT)
            .requestId(seed.requestId())
            .traceId("trace-bundle-" + seed.requestId())
            .params(Map.of("bundleFiles", bundleFiles))
            .build();
    LaunchResponse response = launchService.launch(request);
    assertThat(response.instanceNo()).isNotBlank();

    JobInstanceEntity instance =
        jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
    assertThat(instance).isNotNull();

    return jdbcTemplate.queryForList(
        "select partition_no, source_file_id, template_code, target_ref"
            + " from batch.job_partition where tenant_id = ? and job_instance_id = ?"
            + " order by partition_no",
        TENANT,
        instance.getId());
  }

  @Test
  void bundleImport_expandsIntoBoundHeterogeneousPartitions() {
    List<Map<String, Object>> partitions =
        launchBundleAndReadPartitions(
            "BUNDLE_IMPORT",
            "IMPORT",
            List.of(
                Map.of("sourceFileId", 1001, "templateCode", "TPL_ORDER"),
                Map.of("sourceFileId", 1002, "templateCode", "TPL_CUST")));

    assertThat(partitions).hasSize(2);
    assertThat(partitions.get(0).get("source_file_id")).isEqualTo(1001L);
    assertThat(partitions.get(0).get("template_code")).isEqualTo("TPL_ORDER");
    assertThat(partitions.get(1).get("source_file_id")).isEqualTo(1002L);
    assertThat(partitions.get(1).get("template_code")).isEqualTo("TPL_CUST");
  }

  @Test
  void bundleExport_expandsByTemplateWithoutSourceFile() {
    List<Map<String, Object>> partitions =
        launchBundleAndReadPartitions(
            "BUNDLE_EXPORT",
            "EXPORT",
            List.of(Map.of("templateCode", "EXP_RISK"), Map.of("templateCode", "EXP_TRADE")));

    assertThat(partitions).hasSize(2);
    assertThat(partitions.get(0).get("source_file_id")).isNull();
    assertThat(partitions.get(0).get("template_code")).isEqualTo("EXP_RISK");
    assertThat(partitions.get(1).get("template_code")).isEqualTo("EXP_TRADE");
  }

  @Test
  void bundleDispatch_expandsByFileAndChannelWithoutTemplate() {
    List<Map<String, Object>> partitions =
        launchBundleAndReadPartitions(
            "BUNDLE_DISPATCH",
            "DISPATCH",
            List.of(
                Map.of("sourceFileId", 2001, "targetRef", "CH_SFTP_A"),
                Map.of("sourceFileId", 2002, "targetRef", "CH_OSS_B")));

    assertThat(partitions).hasSize(2);
    assertThat(partitions.get(0).get("source_file_id")).isEqualTo(2001L);
    assertThat(partitions.get(0).get("target_ref")).isEqualTo("CH_SFTP_A");
    assertThat(partitions.get(0).get("template_code")).isNull();
    assertThat(partitions.get(1).get("source_file_id")).isEqualTo(2002L);
    assertThat(partitions.get(1).get("target_ref")).isEqualTo("CH_OSS_B");
  }
}
