package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PREPROCESS 文件编码处理单测：UTF-8 BOM 字节级剥离、未配置 charset 时的 GB18030 探测回退、invalid_char_policy
 * FAIL/REPLACE。全部走内联 content/contentBase64 路径，不需要对象存储容器。
 */
class PreprocessStepEncodingTest {

  private PlatformFileRuntimeRepository runtimeRepository;
  private PreprocessStep step;
  private Map<String, Object> templateConfig;

  @BeforeEach
  void setUp() {
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(true);
    runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    when(runtimeRepository.toLong(any())).thenReturn(1L);
    when(runtimeRepository.loadLatestTemplateConfig(any(), any(), any()))
        .thenAnswer(invocation -> templateConfig == null ? Map.of() : templateConfig);
    S3StorageProperties props = new S3StorageProperties();
    props.setBucket("bucket-1");
    step = new PreprocessStep(
        runtimeRepository,
        security,
        mock(BatchObjectCryptoService.class),
        props,
        mock(BatchObjectStore.class));
    templateConfig = Map.of("file_format_type", "DELIMITED");
  }

  @Test
  void utf8Bom_shouldBeStrippedBeforeDecode() {
    ImportJobContext context = contextWithRawPayload("\uFEFFname,amount\nAlice,100");

    ImportStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getRawPayload()).startsWith("name,amount");
    assertThat(context.getRawPayload()).doesNotContain("\uFEFF");
  }

  @Test
  void gb18030_shouldBeDetected_whenDetectionOptedIn() {
    templateConfig = Map.of("file_format_type", "DELIMITED", "charset_detect", true);
    byte[] gbkBytes = gbk("客户,金额\n张三,100");
    ImportJobContext context = contextWithBase64Payload(gbkBytes, "DELIMITED", "TPL_GBK");

    ImportStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes()).containsEntry("detectedCharset", "GB18030");
    assertThat(context.getRawPayload()).contains("张三");
    assertThat(context.getRawPayload()).doesNotContain("\uFFFD");
  }

  @Test
  void gbkFile_shouldFailFast_whenDetectionNotEnabled() {
    // 默认保守：不配置 charset_detect 时，非 UTF-8 文件保持原有 fail-fast，不允许静默乱码入库。
    ImportJobContext context =
        contextWithBase64Payload(gbk("客户,金额\n张三,100"), "DELIMITED", "TPL_GBK");

    ImportStageResult result = step.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_PREPROCESS_DECODE_FAILED");
    assertThat(context.getAttributes()).doesNotContainKey("detectedCharset");
  }

  @Test
  void explicitCharset_shouldDecodeWithoutDetection() {
    templateConfig = Map.of(
        "file_format_type", "DELIMITED",
        "charset", "GBK");
    ImportJobContext context =
        contextWithBase64Payload(gbk("客户,金额\n张三,100"), "DELIMITED", "TPL_GBK");

    ImportStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes()).doesNotContainKey("detectedCharset");
    assertThat(context.getRawPayload()).contains("张三");
  }

  @Test
  void invalidCharPolicyReplace_shouldReplaceWithReplacementChar() {
    templateConfig = Map.of(
        "file_format_type", "DELIMITED",
        "invalid_char_policy", "REPLACE");
    // 非法 UTF-8 序列：0xC3 后接 '(' 不是合法续字节
    byte[] invalid = new byte[] {'a', ',', 'b', '\n', (byte) 0xC3, (byte) 0x28};
    ImportJobContext context = contextWithBase64Payload(invalid, "DELIMITED", "TPL_REPLACE");

    ImportStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getRawPayload()).contains("\uFFFD");
    assertThat(context.getAttributes().get("replacementCount")).isInstanceOf(Long.class);
  }

  @Test
  void invalidCharPolicyFail_shouldFailDecode_whenBytesInvalid() {
    templateConfig = Map.of(
        "file_format_type", "DELIMITED",
        "invalid_char_policy", "FAIL");
    byte[] invalid = new byte[] {'a', ',', 'b', '\n', (byte) 0xC3, (byte) 0x28};
    ImportJobContext context = contextWithBase64Payload(invalid, "DELIMITED", "TPL_FAIL");

    ImportStageResult result = step.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_PREPROCESS_DECODE_FAILED");
  }

  private static byte[] gbk(String text) {
    return text.getBytes(io.github.pinpols.batch.common.utils.EncodingUtils.GBK);
  }

  private ImportJobContext contextWithRawPayload(String raw) {
    ImportJobContext context = baseContext();
    context.setRawPayload(raw);
    return context;
  }

  private ImportJobContext contextWithBase64Payload(
      byte[] content, String formatType, String templateCode) {
    ImportJobContext context = baseContext();
    context.setRawPayload("");
    Map<String, Object> attrs = context.getAttributes();
    attrs.put(
        PipelineRuntimeKeys.IMPORT_PAYLOAD, payloadWithBase64(content, formatType, templateCode));
    return context;
  }

  private ImportPayload payloadWithBase64(byte[] content, String formatType, String templateCode) {
    return new ImportPayload(
        "FC-ENC",
        "enc.csv",
        "enc.csv",
        "BIZ",
        formatType,
        null,
        null,
        null,
        null,
        "SEND",
        "ref-1",
        "S3",
        null,
        null,
        templateCode,
        "BATCH-1",
        null,
        Base64.getEncoder().encodeToString(content),
        ",",
        1,
        0,
        Boolean.TRUE,
        Map.of());
  }

  private ImportJobContext baseContext() {
    ImportJobContext context = new ImportJobContext();
    context.setTenantId("tenant-enc-test");
    context.setJobCode("ENC_IMPORT");
    context.setWorkerId("worker-enc-1");
    Map<String, Object> attrs = new HashMap<>();
    attrs.put(PipelineRuntimeKeys.FILE_ID, 1L);
    attrs.put(PipelineRuntimeKeys.TASK_ID, 101L);
    context.setAttributes(attrs);
    return context;
  }
}
