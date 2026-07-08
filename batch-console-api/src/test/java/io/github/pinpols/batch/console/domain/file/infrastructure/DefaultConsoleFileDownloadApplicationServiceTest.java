package io.github.pinpols.batch.console.domain.file.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.console.domain.file.mapper.FileErrorRecordMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileRecordMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalRecord;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalRecordResponse;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
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
  private final ConsoleRequestMetadataResolver metadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  // 用真实共享审批客户端 + mock 的 REST 层：保持本测试仍然端到端覆盖"审批必须绑定 fileId"行为。
  private final OrchestratorApprovalClient approvalClient =
      new OrchestratorApprovalClient(orchestratorInternalRestClient, metadataResolver);

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
            approvalClient);
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
  }

  /** 把 orchestrator 审批查询 stub 成返回给定 targetType/targetId/status 的记录。 */
  private void stubApproval(String status, String targetType, String targetId) {
    RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
    when(orchestratorInternalRestClient.build()).thenReturn(restClient);
    ApprovalRecordResponse response =
        new ApprovalRecordResponse(new ApprovalRecord(status, targetType, targetId));
    when(restClient
            .get()
            .uri(anyString(), any(Object[].class))
            .retrieve()
            .body(ApprovalRecordResponse.class))
        .thenReturn(response);
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
