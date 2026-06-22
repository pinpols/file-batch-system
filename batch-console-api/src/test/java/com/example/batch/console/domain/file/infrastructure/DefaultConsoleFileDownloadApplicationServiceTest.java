package com.example.batch.console.domain.file.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.config.S3StorageProperties;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.common.storage.BatchObjectStore;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.domain.file.mapper.FileErrorRecordMapper;
import com.example.batch.console.domain.file.mapper.FileRecordMapper;
import com.example.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import com.example.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.io.ByteArrayInputStream;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link DefaultConsoleFileDownloadApplicationService} 审批门控单测,聚焦审查修复:加密文件下载的审批必须绑定 fileId,
 * 同租户内不得拿一个 file 的 APPROVED 单去解锁另一个 file。
 */
class DefaultConsoleFileDownloadApplicationServiceTest {

  private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
  private final FileRecordMapper fileRecordMapper = mock(FileRecordMapper.class);
  private final FileErrorRecordMapper fileErrorRecordMapper = mock(FileErrorRecordMapper.class);
  private final FileTemplateConfigMapper fileTemplateConfigMapper =
      mock(FileTemplateConfigMapper.class);
  private final S3StorageProperties s3StorageProperties = new S3StorageProperties();
  private final BatchObjectStore objectStore = mock(BatchObjectStore.class);
  private final BatchObjectCryptoService cryptoService = mock(BatchObjectCryptoService.class);
  private final BatchSecurityProperties batchSecurityProperties = new BatchSecurityProperties();
  private final OrchestratorInternalRestClient orchestratorInternalRestClient =
      mock(OrchestratorInternalRestClient.class);

  private DefaultConsoleFileDownloadApplicationService service;

  @BeforeEach
  void setUp() {
    s3StorageProperties.setBucket("default-bucket");
    batchSecurityProperties.setBypassMode(false);
    service =
        new DefaultConsoleFileDownloadApplicationService(
            tenantGuard,
            fileRecordMapper,
            fileErrorRecordMapper,
            fileTemplateConfigMapper,
            s3StorageProperties,
            objectStore,
            cryptoService,
            batchSecurityProperties,
            orchestratorInternalRestClient);
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
  }

  /** 把 orchestrator 审批查询 stub 成返回给定 targetType/targetId/status 的记录。 */
  private void stubApproval(String status, String targetType, String targetId) {
    RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
    when(orchestratorInternalRestClient.build()).thenReturn(restClient);
    String json =
        "{\"record\":{\"approvalStatus\":\""
            + status
            + "\",\"targetType\":\""
            + targetType
            + "\",\"targetId\":\""
            + targetId
            + "\"}}";
    Class<Object> responseClass = recordResponseClass();
    Object record = JsonUtils.fromJson(json, responseClass);
    when(restClient.get().uri(anyString(), any(Object[].class)).retrieve().body(responseClass))
        .thenReturn(record);
  }

  /** 反射拿到 service 内私有 ApprovalRecordResponse 类型,供 stub 反序列化目标类型用。 */
  @SuppressWarnings("unchecked")
  private static <T> Class<T> recordResponseClass() {
    for (Class<?> nested :
        DefaultConsoleFileDownloadApplicationService.class.getDeclaredClasses()) {
      if (nested.getSimpleName().equals("ApprovalRecordResponse")) {
        return (Class<T>) nested;
      }
    }
    throw new IllegalStateException("ApprovalRecordResponse not found");
  }

  private void stubEncryptedFile(long fileId) {
    when(fileRecordMapper.selectFileRecordById("t1", fileId))
        .thenReturn(
            Map.of(
                "storage_bucket",
                "bucket-a",
                "storage_path",
                "uploads/t1/" + fileId + ".bin",
                "file_name",
                "f" + fileId + ".csv",
                "mime_type",
                "application/octet-stream"));
    when(fileRecordMapper.selectTemplateCodeByFileId("t1", fileId)).thenReturn("TPL");
    when(fileTemplateConfigMapper.selectSecurityFlagsByTemplateCode("t1", "TPL"))
        .thenReturn(Map.of("content_encryption_enabled", Boolean.TRUE));
  }

  @Test
  void shouldDownload_whenApprovalTargetsSameFile() throws Exception {
    stubEncryptedFile(100L);
    stubApproval("APPROVED", "FILE", "100");
    when(objectStore.get("bucket-a", "uploads/t1/100.bin"))
        .thenReturn(new ByteArrayInputStream("ciphertext".getBytes()));
    when(cryptoService.decryptIfNeeded(any()))
        .thenReturn(new ByteArrayInputStream("plaintext".getBytes()));

    var response = service.download("t1", 100L, "AP-100");

    assertThat(response.getStatusCode().value()).isEqualTo(200);
  }

  @Test
  void shouldReject403_whenApprovalTargetsDifferentFile() {
    stubEncryptedFile(100L);
    // 审批单是给 file 200 批的,却被拿来下载 file 100 → 同租越权,必须 403。
    stubApproval("APPROVED", "FILE", "200");

    assertThatThrownBy(() -> service.download("t1", 100L, "AP-200"))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
  }

  @Test
  void shouldReject403_whenApprovalTargetTypeIsNotFile() {
    stubEncryptedFile(100L);
    // targetType 非 FILE（如对账/导出单)同样不能解锁文件下载。
    stubApproval("APPROVED", "EXPORT", "100");

    assertThatThrownBy(() -> service.download("t1", 100L, "AP-X"))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
  }

  @Test
  void shouldReject409_whenApprovalNotApprovedYet() {
    stubEncryptedFile(100L);
    stubApproval("PENDING", "FILE", "100");

    assertThatThrownBy(() -> service.download("t1", 100L, "AP-100"))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.STATE_CONFLICT);
  }
}
