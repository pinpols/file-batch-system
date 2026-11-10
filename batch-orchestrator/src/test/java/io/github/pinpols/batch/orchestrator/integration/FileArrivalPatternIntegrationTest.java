package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipIdentity;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorContext;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorProbeStatus;
import io.github.pinpols.batch.orchestrator.infrastructure.sensor.FileArrivalSensorPolicy;
import io.github.pinpols.batch.orchestrator.mapper.FileGovernanceMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("文件到达传感器匹配规则与数据库语义")
class FileArrivalPatternIntegrationTest extends AbstractIntegrationTest {
  private final FileArrivalSensorPolicy policy;

  private final JdbcTemplate jdbc;
  private final FileGovernanceMapper fileGovernanceMapper;
  private final ImportIngressOwnershipIdentity ownershipIdentity;
  private final S3StorageProperties s3StorageProperties;

  @Autowired
  FileArrivalPatternIntegrationTest(
      FileArrivalSensorPolicy policy,
      JdbcTemplate jdbc,
      FileGovernanceMapper fileGovernanceMapper,
      ImportIngressOwnershipIdentity ownershipIdentity,
      S3StorageProperties s3StorageProperties) {
    this.policy = policy;
    this.jdbc = jdbc;
    this.fileGovernanceMapper = fileGovernanceMapper;
    this.ownershipIdentity = ownershipIdentity;
    this.s3StorageProperties = s3StorageProperties;
  }

  @ParameterizedTest
  @CsvSource({
    "invoice_2026*.csv, invoiceX2026x.csv, invoice_2026x.csv",
    "amount%2026*.csv, amountX2026x.csv, amount%2026x.csv",
    "batch\\2026*.csv, batch2026x.csv, batch\\2026x.csv",
    "warn!2026*.csv, warn2026x.csv, warn!2026x.csv"
  })
  @DisplayName("仅星号为通配符, 下划线百分号与反斜线按字面匹配")
  void shouldMatchOnlyLiteralPatternCharacters(String pattern, String decoy, String matching) {
    String tenant = "sensor-pattern-" + System.nanoTime();
    SensorContext context = new SensorContext(
        tenant,
        1L,
        Map.of("pattern", pattern, "maxAgeSeconds", 3600),
        Map.of(),
        Duration.ofMinutes(1),
        null);
    seed(tenant, decoy);
    var first = RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(context));
    assertThat(first.status()).isEqualTo(SensorProbeStatus.NOT_YET);
    seed(tenant, matching);
    var second = RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(context));
    assertThat(second.status()).isEqualTo(SensorProbeStatus.MATCHED);
    assertThat(second.output()).containsEntry("fileName", matching);
  }

  @Test
  @DisplayName("托管上传会话须确认文件到达后才能命中文件等待")
  void shouldWaitForManagedUploadConfirmation() {
    String tenant = "sensor-upload-" + System.nanoTime();
    String fileName = "orders.csv";
    String insertFileSql = """
        insert into batch.file_record (tenant_id, file_category, file_name, file_format_type,
          storage_type, storage_path, source_type, file_status, metadata_json)
        values (?, 'INPUT', ?, 'DELIMITED', 'LOCAL', ?, 'UPLOAD', 'RECEIVED',
          '{"uploadMode":"APP_MANAGED","arrivalState":"WAITING_ARRIVAL"}'::jsonb)
        """;
    jdbc.update(insertFileSql, tenant, fileName, tenant + "/" + fileName);
    SensorContext context = new SensorContext(
        tenant,
        1L,
        Map.of("pattern", fileName, "maxAgeSeconds", 3600),
        Map.of(),
        Duration.ofMinutes(1),
        null);

    var beforeConfirmation =
        RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(context));
    assertThat(beforeConfirmation.status()).isEqualTo(SensorProbeStatus.NOT_YET);

    String confirmArrivalSql = """
        update batch.file_record
           set metadata_json = metadata_json || '{"arrivalConfirmedAt":"2026-11-09T00:00:00Z"}'::jsonb
         where tenant_id = ? and file_name = ?
        """;
    jdbc.update(confirmArrivalSql, tenant, fileName);
    var afterConfirmation =
        RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(context));
    assertThat(afterConfirmation.status()).isEqualTo(SensorProbeStatus.MATCHED);
    assertThat(afterConfirmation.output()).containsEntry("fileName", fileName);
  }

  @Test
  @DisplayName("受管入口已有对账记录须等待扫描准入，普通孤儿和历史文件保持可见")
  void shouldWaitForScannerAcceptanceOfExistingReconciledFileInOwnedScope() {
    String tenant = "sensor-owned-" + System.nanoTime();
    String prefix = "ingress/" + tenant + "/";
    String bucket = s3StorageProperties.getBucket();
    var objectMapper = JsonUtils.newDefaultMapper();
    String storageId = ownershipIdentity.storageId(s3StorageProperties, objectMapper);
    String scopeId = ImportIngressOwnershipIdentity.scopeId(storageId, prefix, objectMapper);
    String insertScopeSql = """
        insert into batch.import_ingress_ownership
          (scope_id, storage_id, bucket_name, object_prefix, state)
        values (?, ?, ?, ?, 'ACTIVE')
        """;
    jdbc.update(insertScopeSql, scopeId, storageId, bucket, prefix);
    String insertBindingSql = """
        insert into batch.import_ingress_ownership_binding
          (scope_id, binding_type, tenant_id, object_prefix, file_group_code, manifest_key_template)
        values (?, 'MANIFEST', ?, ?, 'GROUP_A', ?)
        """;
    jdbc.update(
        insertBindingSql,
        scopeId,
        tenant,
        prefix,
        "manifests/" + tenant + "/group-{bizDateCompact}.batch.json");
    String insertRetiredBindingSql = """
        insert into batch.import_ingress_ownership_binding
          (scope_id, binding_type, tenant_id, object_prefix, file_group_code, manifest_key_template, enabled)
        values (?, 'MANIFEST', ?, ?, 'GROUP_A', ?, false)
        """;
    jdbc.update(
        insertRetiredBindingSql,
        scopeId,
        tenant,
        prefix,
        "manifests/" + tenant + "/old-{bizDate}.batch.json");

    String insertFileSql = """
        insert into batch.file_record (tenant_id, file_category, file_name, file_format_type,
          storage_type, storage_bucket, storage_path, source_type, file_status,
          legacy_channel_fallback, metadata_json)
        values (?, 'INPUT', ?, 'DELIMITED', 'S3', ?, ?, 'SYSTEM', 'RECEIVED', ?, ?::jsonb)
        """;
    String waitingMetadata = """
        {"reconciled":true,"fileGroupCode":"GROUP_A","arrivalState":"WAITING_ARRIVAL",
         "requiredFileSet":"ordinary.csv,legacy.csv,accepted.csv,pending.csv"}
        """;
    String acceptedMetadata = """
        {"reconciled":true,"scannerAccepted":true,"fileGroupCode":"GROUP_A",
         "arrivalState":"WAITING_ARRIVAL",
         "requiredFileSet":"ordinary.csv,legacy.csv,accepted.csv,pending.csv"}
        """;
    jdbc.update(
        insertFileSql,
        tenant,
        "ordinary.csv",
        bucket,
        "other/" + tenant + "/ordinary.csv",
        false,
        waitingMetadata);
    jdbc.update(
        insertFileSql, tenant, "legacy.csv", bucket, prefix + "legacy.csv", true, waitingMetadata);
    jdbc.update(
        insertFileSql,
        tenant,
        "accepted.csv",
        bucket,
        prefix + "accepted.csv",
        false,
        acceptedMetadata);
    jdbc.update(
        insertFileSql,
        tenant,
        "pending.csv",
        bucket,
        prefix + "pending.csv",
        false,
        waitingMetadata);
    jdbc.update(
        insertFileSql,
        tenant,
        "group-20261109.batch.json",
        bucket,
        "manifests/" + tenant + "/group-20261109.batch.json",
        false,
        "{\"reconciled\":true}");
    jdbc.update(
        insertFileSql,
        tenant,
        "old-2026-11-09.batch.json",
        bucket,
        "manifests/" + tenant + "/old-2026-11-09.batch.json",
        false,
        "{\"reconciled\":true}");

    for (String visibleName : new String[] {"ordinary.csv", "legacy.csv", "accepted.csv"}) {
      SensorContext context = new SensorContext(
          tenant,
          1L,
          Map.of("pattern", visibleName, "maxAgeSeconds", 3600),
          Map.of(),
          Duration.ofMinutes(1),
          null);
      var result = RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(context));
      assertThat(result.status()).as(visibleName).isEqualTo(SensorProbeStatus.MATCHED);
    }
    SensorContext pendingContext = new SensorContext(
        tenant,
        1L,
        Map.of("pattern", "pending.csv", "maxAgeSeconds", 3600),
        Map.of(),
        Duration.ofMinutes(1),
        null);
    var pendingResult =
        RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(pendingContext));
    assertThat(pendingResult.status()).isEqualTo(SensorProbeStatus.NOT_YET);

    SensorContext wildcardContext = new SensorContext(
        tenant,
        1L,
        Map.of("pattern", "*.csv", "maxAgeSeconds", 3600),
        Map.of(),
        Duration.ofMinutes(1),
        null);
    var wildcardResult =
        RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(wildcardContext));
    assertThat(wildcardResult.status()).isEqualTo(SensorProbeStatus.MATCHED);
    assertThat(wildcardResult.output()).containsEntry("fileName", "accepted.csv");

    SensorContext manifestContext = new SensorContext(
        tenant,
        1L,
        Map.of("pattern", "group-20261109.batch.json", "maxAgeSeconds", 3600),
        Map.of(),
        Duration.ofMinutes(1),
        null);
    var manifestResult =
        RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(manifestContext));
    assertThat(manifestResult.status()).isEqualTo(SensorProbeStatus.NOT_YET);
    SensorContext retiredManifestContext = new SensorContext(
        tenant,
        1L,
        Map.of("pattern", "old-2026-11-09.batch.json", "maxAgeSeconds", 3600),
        Map.of(),
        Duration.ofMinutes(1),
        null);
    var retiredManifestResult =
        RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(retiredManifestContext));
    assertThat(retiredManifestResult.status()).isEqualTo(SensorProbeStatus.NOT_YET);

    var candidates =
        fileGovernanceMapper.selectArrivalGovernanceCandidates(1000, storageId).stream()
            .filter(row -> tenant.equals(row.tenantId()))
            .map(row -> row.fileName())
            .toList();
    assertThat(candidates).containsExactlyInAnyOrder("ordinary.csv", "legacy.csv", "accepted.csv");
    var members =
        fileGovernanceMapper.selectArrivalGroupFiles(tenant, "GROUP_A", null, storageId).stream()
            .map(row -> row.fileName())
            .toList();
    assertThat(members).containsExactlyInAnyOrder("ordinary.csv", "legacy.csv", "accepted.csv");
    var summaries =
        fileGovernanceMapper.selectArrivalGroupSummaries(tenant, "GROUP_A", null, storageId);
    assertThat(summaries)
        .singleElement()
        .satisfies(row -> assertThat(row.arrivedCount()).isEqualTo(3L));

    String acceptSql = """
        update batch.file_record
           set metadata_json = metadata_json || '{"scannerAccepted":true}'::jsonb
         where tenant_id = ? and storage_path = ?
        """;
    jdbc.update(acceptSql, tenant, prefix + "pending.csv");
    var acceptedResult =
        RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(pendingContext));
    assertThat(acceptedResult.status()).isEqualTo(SensorProbeStatus.MATCHED);
    var acceptedMembers =
        fileGovernanceMapper.selectArrivalGroupFiles(tenant, "GROUP_A", null, storageId);
    assertThat(acceptedMembers).hasSize(4);

    jdbc.update(
        insertFileSql,
        tenant,
        "retired.csv",
        bucket,
        prefix + "retired.csv",
        false,
        "{\"reconciled\":true}");
    String changeScopeStateSql = """
        update batch.import_ingress_ownership set state = ? where scope_id = ?
        """;
    jdbc.update(changeScopeStateSql, "RETIRED", scopeId);
    SensorContext retiredContext = new SensorContext(
        tenant,
        1L,
        Map.of("pattern", "retired.csv", "maxAgeSeconds", 3600),
        Map.of(),
        Duration.ofMinutes(1),
        null);
    var retiredResult =
        RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(retiredContext));
    assertThat(retiredResult.status()).isEqualTo(SensorProbeStatus.NOT_YET);
    jdbc.update(changeScopeStateSql, "RELEASED", scopeId);
    var releasedResult =
        RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(retiredContext));
    assertThat(releasedResult.status()).isEqualTo(SensorProbeStatus.MATCHED);
  }

  @Test
  @DisplayName("指定渠道只匹配同租户的可信渠道身份，任务元数据不能冒充渠道")
  void shouldMatchTrustedChannelWithinTenant() {
    String tenant = "sensor-channel-" + System.nanoTime();
    String fileName = "channel-test.csv";
    SensorContext context = new SensorContext(
        tenant,
        1L,
        Map.of("pattern", fileName, "maxAgeSeconds", 3600, "channelCode", "BANK-SFTP"),
        Map.of(),
        Duration.ofMinutes(1),
        null);

    seedChannelFile(tenant + "-other", fileName, "SYSTEM", "BANK-SFTP", false, "{}");
    seedChannelFile(tenant, fileName, "SYSTEM", "bank_sftp", false, "{}");
    seedChannelFile(tenant, fileName, "UPLOAD", null, false, "{\"channelCode\":\"BANK-SFTP\"}");
    seedChannelFile(tenant, fileName, "API", null, true, "{}");
    seedChannelFile(tenant, fileName, "SFTP", null, true, "{}");
    var beforeMatching = RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(context));
    assertThat(beforeMatching.status()).isEqualTo(SensorProbeStatus.NOT_YET);

    Long expectedId = seedChannelFile(tenant, fileName, "SYSTEM", "BANK-SFTP", false, "{}");
    var afterMatching = RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(context));
    assertThat(afterMatching.status()).isEqualTo(SensorProbeStatus.MATCHED);
    assertThat(afterMatching.output()).containsEntry("fileId", expectedId);
  }

  @Test
  @DisplayName("只有迁移前的历史文件可以按旧来源类型回退并标记风险")
  void shouldMarkLegacyChannelFallback() {
    String tenant = "sensor-legacy-" + System.nanoTime();
    String fileName = "legacy-test.csv";
    SensorContext context = new SensorContext(
        tenant,
        1L,
        Map.of("pattern", fileName, "maxAgeSeconds", 3600, "channelCode", "sftp_bank"),
        Map.of(),
        Duration.ofMinutes(1),
        null);

    seedChannelFile(tenant, fileName, "SFTP", null, false, "{}");
    seedChannelFile(tenant, fileName, "API", null, true, "{}");
    var beforeLegacyMatch =
        RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(context));
    assertThat(beforeLegacyMatch.status()).isEqualTo(SensorProbeStatus.NOT_YET);

    Long expectedId = seedChannelFile(tenant, fileName, "SFTP", null, true, "{}");
    var afterLegacyMatch =
        RlsTenantContextHolder.runWithTenant(tenant, () -> policy.probe(context));
    assertThat(afterLegacyMatch.status()).isEqualTo(SensorProbeStatus.MATCHED);
    assertThat(afterLegacyMatch.output())
        .containsEntry("fileId", expectedId)
        .containsEntry("legacyChannelFallback", true);
  }

  @Test
  @DisplayName("托管上传只信任同租户已启用的渠道")
  void shouldResolveOnlyEnabledChannelForTenant() {
    String tenant = "upload-channel-" + System.nanoTime();
    String insertChannelSql = """
        insert into batch.file_channel_config (tenant_id, channel_code, channel_name, channel_type,
          config_json, enabled)
        values (?, ?, ?, 'LOCAL', '{}'::jsonb, ?)
        """;
    jdbc.update(insertChannelSql, tenant, "enabled", "enabled", true);
    jdbc.update(insertChannelSql, tenant, "disabled", "disabled", false);

    assertThat(fileGovernanceMapper.existsEnabledFileChannel(tenant, "enabled")).isTrue();
    assertThat(fileGovernanceMapper.existsEnabledFileChannel(tenant, "disabled"))
        .isFalse();
    assertThat(fileGovernanceMapper.existsEnabledFileChannel(tenant + "-other", "enabled"))
        .isFalse();
    assertThat(fileGovernanceMapper.existsEnabledFileChannel(tenant, "missing")).isFalse();
  }

  private Long seedChannelFile(
      String tenant,
      String fileName,
      String sourceType,
      String channelCode,
      boolean legacyFallback,
      String metadata) {
    String insertFileSql = """
        insert into batch.file_record (tenant_id, file_category, file_name, file_format_type,
          storage_type, storage_path, source_type, file_status, arrival_channel_code,
          legacy_channel_fallback, metadata_json)
        values (?, 'INPUT', ?, 'DELIMITED', 'LOCAL', ?, ?, 'RECEIVED', ?, ?, ?::jsonb)
        returning id
        """;
    return jdbc.queryForObject(
        insertFileSql,
        Long.class,
        tenant,
        fileName,
        tenant + "/" + System.nanoTime() + "/" + fileName,
        sourceType,
        channelCode,
        legacyFallback,
        metadata);
  }

  private void seed(String tenant, String name) {
    String insertFileSql = """
        insert into batch.file_record (tenant_id,file_category,file_name,file_format_type,storage_type,
          storage_path,source_type,file_status)
        values (?, 'INPUT', ?, 'DELIMITED', 'LOCAL', ?, 'SYSTEM', 'RECEIVED')
        """;
    jdbc.update(insertFileSql, tenant, name, tenant + "/" + name);
  }
}
