package io.github.pinpols.batch.worker.exports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineDefinitionRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.support.FrozenFileTemplateResolver;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportPayload;
import io.github.pinpols.batch.worker.exports.infrastructure.S3ExportStorage;
import io.github.pinpols.batch.worker.exports.plugin.ExportDataPluginRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("导出产物归属:新 Job 隔离对象与临时路径,重领只复用本 Job 的已登记产物")
class ExportArtifactIdentityTest {

  @Mock private PlatformPipelineDefinitionRepository definitions;
  @Mock private SecretPayloadProtector protector;
  @Mock private PlatformFileRecordRepository records;
  @Mock private S3ExportStorage storage;
  @Mock private BatchObjectCryptoService crypto;
  @Mock private PlatformPipelineRunRepository pipelineRuns;
  @Mock private PlatformFileAuditRepository fileAudits;
  @Mock private ExportDataPluginRegistry plugins;
  @Mock private TaskExecutionClient taskClient;

  @TempDir Path tempDir;

  @Test
  @DisplayName("相同业务参数的新 Job 使用不同最终和临时键,同 Job 重领保持稳定")
  void shouldIsolateObjectKeys_whenJobInstanceChanges() {
    when(definitions.loadLatestTemplateConfig("t1", "T1", "EXPORT"))
        .thenReturn(Map.of("file_format_type", "DELIMITED", "naming_rule", "fixed.csv"));
    FrozenFileTemplateResolver templates = new FrozenFileTemplateResolver(definitions, protector);
    PrepareStep prepare = new PrepareStep(new ObjectMapper(), templates);
    ExportJobContext first = context(101L);
    ExportJobContext repeated = context(101L);
    ExportJobContext next = context(202L);
    var firstResult = prepare.execute(first);
    var repeatedResult = prepare.execute(repeated);
    var nextResult = prepare.execute(next);
    assertThat(firstResult.success()).isTrue();
    assertThat(repeatedResult.success()).isTrue();
    assertThat(nextResult.success()).isTrue();
    Object firstKey = first.getAttributes().get(PipelineRuntimeKeys.OBJECT_NAME);
    Object repeatedKey = repeated.getAttributes().get(PipelineRuntimeKeys.OBJECT_NAME);
    Object nextKey = next.getAttributes().get(PipelineRuntimeKeys.OBJECT_NAME);
    assertThat(firstKey).isEqualTo(repeatedKey).isNotEqualTo(nextKey);
    Object firstTempKey = first.getAttributes().get(PipelineRuntimeKeys.TEMP_OBJECT_NAME);
    Object repeatedTempKey = repeated.getAttributes().get(PipelineRuntimeKeys.TEMP_OBJECT_NAME);
    Object nextTempKey = next.getAttributes().get(PipelineRuntimeKeys.TEMP_OBJECT_NAME);
    assertThat(firstTempKey).isEqualTo(repeatedTempKey).isNotEqualTo(nextTempKey);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("显式路径归属其它 Job 或缺少归属时,不读取旧对象也不丢弃本次内容")
  void shouldRejectCrossJobArtifactReuse_whenExplicitObjectKeyAlreadyExists(boolean ownerPresent)
      throws Exception {
    Path generated = tempDir.resolve("generated.csv");
    Files.writeString(generated, "new business data");
    when(records.loadFileRecordByStoragePath("t1", "exports", "shared.csv"))
        .thenReturn(Map.of("checksum_type", "SHA-256", "checksum_value", "old-digest",
            "metadata_json", ownerPresent ? Map.of("jobInstanceId", 101L) : Map.of()));
    S3StorageProperties properties = new S3StorageProperties();
    properties.setBucket("exports");
    ExportJobContext current = context(202L);
    current.getAttributes().put(PipelineRuntimeKeys.OBJECT_NAME, "shared.csv");
    current.getAttributes().put(PipelineRuntimeKeys.GENERATED_FILE_PATH, generated.toString());
    StoreStep store = new StoreStep(storage, crypto, null, records, properties);

    var result = store.execute(current);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_STORE_ARTIFACT_CONFLICT");
    assertThat(result.message()).contains("Job");
    assertThat(generated).hasContent("new business data");
    verifyNoInteractions(storage, crypto);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("同 Job 的 Map 或 JSON 格式归属都可恢复已校验产物,不覆盖远端")
  void shouldReuseOwnArtifact_whenMetadataComesFromMapOrJson(boolean jsonMetadata) throws Exception {
    Path generated = tempDir.resolve("retry.csv");
    Files.writeString(generated, "regenerated after reclaim");
    String checksum = "a".repeat(64);
    Object metadata = jsonMetadata ? "{\"jobInstanceId\":101}" : Map.of("jobInstanceId", 101L);
    when(records.loadFileRecordByStoragePath("t1", "exports", "own.csv"))
        .thenReturn(Map.of("checksum_type", "SHA-256", "checksum_value", checksum,
            "file_size_bytes", 42L, "metadata_json", metadata));
    when(storage.sha256Hex("own.csv")).thenReturn(checksum);
    S3StorageProperties properties = new S3StorageProperties();
    properties.setBucket("exports");
    ExportJobContext current = context(101L);
    current.getAttributes().put(PipelineRuntimeKeys.OBJECT_NAME, "own.csv");
    current.getAttributes().put(PipelineRuntimeKeys.GENERATED_FILE_PATH, generated.toString());
    StoreStep store = new StoreStep(storage, crypto, null, records, properties);

    var result = store.execute(current);

    assertThat(result.success()).isTrue();
    assertThat(generated).doesNotExist();
    verify(storage, never()).writeObject(anyString(), any(Path.class), anyString());
    verify(storage, never()).copyObject(anyString(), anyString());
    verifyNoInteractions(crypto);
  }

  @Test
  @DisplayName("登记阶段也拒绝其它 Job 的既有对象,不绑定旧文件或执行回调")
  void shouldRejectForeignArtifactAtRegistration_whenStoredChecksumMatches() throws Exception {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setBucket("exports");
    ExportJobContext current = context(202L);
    ObjectMapper mapper = new ObjectMapper();
    ExportPayload payload = mapper.readValue(current.getRawPayload(), ExportPayload.class);
    current.getAttributes().put("exportPayload", payload);
    current.getAttributes().put("exportBatch", Map.of("id", 7L));
    current.getAttributes().put(PipelineRuntimeKeys.OBJECT_NAME, "foreign.csv");
    current.getAttributes().put(PipelineRuntimeKeys.CHECKSUM_VALUE, "same-digest");
    when(records.existsFileRecordByStoragePath("t1", "exports", "foreign.csv")).thenReturn(true);
    when(records.loadFileRecordByStoragePath("t1", "exports", "foreign.csv"))
        .thenReturn(Map.of("id", 1L, "checksum_value", "same-digest",
            "metadata_json", Map.of("jobInstanceId", 101L)));
    RegisterStep register = new RegisterStep(
        records, pipelineRuns, fileAudits, plugins, properties, taskClient);

    var result = register.execute(current);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_REGISTER_ARTIFACT_CONFLICT");
    verifyNoInteractions(pipelineRuns, fileAudits, plugins, taskClient);
  }

  private static ExportJobContext context(long jobInstanceId) {
    ExportJobContext context = new ExportJobContext();
    context.setTenantId("t1");
    context.setJobCode("EXPORT_TEST");
    context.setBizDate("2026-10-23");
    context.setRawPayload("""
        {"templateCode":"T1","batchNo":"B1","bizType":"BIZ","bizDate":"2026-10-23"}
        """);
    context.getAttributes().put(PipelineRuntimeKeys.JOB_INSTANCE_ID, jobInstanceId);
    return context;
  }
}
