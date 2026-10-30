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
import io.github.pinpols.batch.console.domain.file.entity.FileErrorRecordEntity;
import io.github.pinpols.batch.console.domain.file.mapper.FileErrorRecordMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileRecordMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.view.FileRecordStorageView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateSecurityFlagsView;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalRecord;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalRecordResponse;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

/**
 * {@link DefaultConsoleFileDownloadApplicationService} 审批门控单测,聚焦审查修复:加密文件下载的审批必须绑定 fileId,
 * 同租户内不得拿一个 file 的 APPROVED 单去解锁另一个 file。
 */
@DisplayName("文件下载与错误记录导出: 审批绑定和 CSV 转义")
@ExtendWith(MockitoExtension.class)
class DefaultConsoleFileDownloadApplicationServiceTest {

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @Mock
  private FileRecordMapper fileRecordMapper;

  @Mock
  private FileErrorRecordMapper fileErrorRecordMapper;

  @Mock
  private FileTemplateConfigMapper fileTemplateConfigMapper;

  private S3StorageProperties s3StorageProperties;

  @Mock
  private BatchObjectStore objectStore;

  @Mock
  private BatchObjectCryptoService cryptoService;

  private BatchSecurityProperties batchSecurityProperties;

  @Mock
  private OrchestratorInternalRestClient orchestratorInternalRestClient;

  @Mock
  private ConsoleRequestMetadataResolver metadataResolver;
  // 用真实共享审批客户端 + mock 的 REST 层：保持本测试仍然端到端覆盖"审批必须绑定 fileId"行为。
  private OrchestratorApprovalClient approvalClient;

  private DefaultConsoleFileDownloadApplicationService service;

  @BeforeEach
  void setUp() {
    s3StorageProperties = new S3StorageProperties();
    batchSecurityProperties = new BatchSecurityProperties();
    approvalClient =
        new OrchestratorApprovalClient(orchestratorInternalRestClient, metadataResolver);
    s3StorageProperties.setBucket("default-bucket");
    batchSecurityProperties.setBypassMode(false);
    service = new DefaultConsoleFileDownloadApplicationService(
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
    when(orchestratorInternalRestClient.client()).thenReturn(restClient);
    ApprovalRecordResponse response =
        new ApprovalRecordResponse(new ApprovalRecord(status, targetType, targetId, null, null));
    when(restClient
            .get()
            .uri(anyString(), any(Object[].class))
            .retrieve()
            .body(ApprovalRecordResponse.class))
        .thenReturn(response);
  }

  @Test
  @DisplayName("导出错误记录时单独回车保留在带引号的 CSV 字段中")
  void shouldQuoteStandaloneCarriageReturnInErrorExport() throws Exception {
    FileErrorRecordEntity error = new FileErrorRecordEntity();
    error.setId(1L);
    error.setFileId(100L);
    error.setRawRecord("first\rsecond");
    when(fileErrorRecordMapper.selectByQuery(any())).thenReturn(List.of(error));

    var response = service.exportFileErrors("t1", 100L, null);
    String csv =
        new String(response.getBody().getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(csv).contains(",\"first\rsecond\",");
  }

  @Test
  @DisplayName("导出错误记录时逗号、引号和换行仍按 CSV 规则转义")
  void shouldPreserveExistingCsvEscaping() throws Exception {
    FileErrorRecordEntity error = new FileErrorRecordEntity();
    error.setId(1L);
    error.setFileId(100L);
    error.setRawRecord("a,\"b\"\nc");
    when(fileErrorRecordMapper.selectByQuery(any())).thenReturn(List.of(error));

    var response = service.exportFileErrors("t1", 100L, null);
    String csv =
        new String(response.getBody().getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(csv).contains(",\"a,\"\"b\"\"\nc\",");
  }

  private void stubEncryptedFile(long fileId) {
    when(fileRecordMapper.selectFileRecordById("t1", fileId))
        .thenReturn(new FileRecordStorageView(
            fileId,
            "t1",
            "f" + fileId + ".csv",
            "application/octet-stream",
            "S3",
            "uploads/t1/" + fileId + ".bin",
            "bucket-a",
            null));
    when(fileRecordMapper.selectTemplateCodeByFileId("t1", fileId)).thenReturn("TPL");
    when(fileTemplateConfigMapper.selectSecurityFlagsByTemplateCode("t1", "TPL"))
        .thenReturn(FileTemplateSecurityFlagsView.builder()
            .contentEncryptionEnabled(true)
            .build());
  }

  @Test
  @DisplayName("审批单目标为本次文件时允许下载")
  void shouldDownload_whenApprovalTargetsSameFile() {
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
  @DisplayName("审批单目标为其它文件时按无权访问拒绝")
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
  @DisplayName("审批单目标类型非文件时按无权访问拒绝")
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
  @DisplayName("审批单尚未通过时按状态冲突拒绝")
  void shouldReject409_whenApprovalNotApprovedYet() {
    stubEncryptedFile(100L);
    stubApproval("PENDING", "FILE", "100");

    assertThatThrownBy(() -> service.download("t1", 100L, "AP-100"))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.STATE_CONFLICT);
  }
}
