package io.github.pinpols.batch.orchestrator.application.service.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.RunMode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.storage.ObjectNotFoundException;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.config.FileGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.command.ArrivalGroupGovernanceCommand;
import io.github.pinpols.batch.orchestrator.domain.command.FileGovernanceCommand;
import io.github.pinpols.batch.orchestrator.domain.command.FileUploadSessionCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceArrivalViews;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import io.github.pinpols.batch.orchestrator.infrastructure.file.S3GovernanceStorage;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 单元测试:{@link DefaultFileGovernanceService}。
 *
 * <p>覆盖:5 个 @Transactional 公共方法的 happy path + 主要错误分支;校验状态机/安静期/审计写入/outbox 写入等关键侧效。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文件治理服务: 状态流转, 失败审计, 下载授权与到达组操作口径")
class DefaultFileGovernanceServiceTest {

  @Mock
  private FileGovernanceRepository fileGovernanceRepository;

  @Mock
  private JobTaskMapper jobTaskMapper;

  @Mock
  private JobPartitionMapper jobPartitionMapper;

  @Mock
  private JobInstanceMapper jobInstanceMapper;

  @Mock
  private TaskDispatchOutboxService taskDispatchOutboxService;

  @Mock
  private S3GovernanceStorage s3GovernanceStorage;

  @Mock
  private FileGovernanceCommitService fileGovernanceCommitService;

  private final FileGovernanceProperties fileGovernanceProperties = new FileGovernanceProperties();
  private final BatchSecurityProperties batchSecurityProperties = new BatchSecurityProperties();

  private DefaultFileGovernanceService service;

  @BeforeEach
  void setUp() {
    service = new DefaultFileGovernanceService(
        fileGovernanceRepository,
        jobTaskMapper,
        jobPartitionMapper,
        jobInstanceMapper,
        taskDispatchOutboxService,
        fileGovernanceProperties,
        s3GovernanceStorage,
        batchSecurityProperties,
        fileGovernanceCommitService);
  }

  // ── validateCommand / validateArrivalGroupCommand ────────────────────────

  @Test
  @DisplayName("归档时租户标识为空则抛出参数非法异常")
  void shouldThrow_whenTenantIdBlank_onArchive() {
    FileGovernanceCommand cmd = baseCommand().tenantId("").build();
    assertThatThrownBy(() -> service.archiveFile(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.INVALID_ARGUMENT);
  }

  @Test
  @DisplayName("删除时文件标识为空则抛出参数非法异常")
  void shouldThrow_whenFileIdNull_onDelete() {
    FileGovernanceCommand cmd = baseCommand().fileId(null).build();
    assertThatThrownBy(() -> service.deleteFile(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("到达组操作时文件组编码为空则抛出参数非法异常")
  void shouldThrow_whenArrivalGroupCodeBlank() {
    ArrivalGroupGovernanceCommand cmd = ArrivalGroupGovernanceCommand.builder()
        .tenantId("t1")
        .fileGroupCode("")
        .action("CONTINUE_WAITING")
        .build();
    assertThatThrownBy(() -> service.operateArrivalGroup(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("到达组操作未给出动作时抛出参数非法异常")
  void shouldThrow_whenArrivalActionBlank() {
    ArrivalGroupGovernanceCommand cmd = ArrivalGroupGovernanceCommand.builder()
        .tenantId("t1")
        .fileGroupCode("grp")
        .action("")
        .build();
    assertThatThrownBy(() -> service.operateArrivalGroup(cmd)).isInstanceOf(BizException.class);
  }

  // ── archiveFile / deleteFile (changeFileStatus) ──────────────────────────

  @Test
  @DisplayName("状态流转允许归档时更新为已归档, 并写成功审计")
  void shouldArchiveFile_whenStatusTransitionAllowed() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("file_status", FileStatus.LOADED.code()));
    when(fileGovernanceRepository.countActivePipelineInstances("t1", 1L)).thenReturn(0L);
    when(fileGovernanceRepository.countPendingDispatchRecords("t1", 1L)).thenReturn(0L);
    when(fileGovernanceRepository.updateFileStatus(
            eq("t1"), eq(1L), eq(FileStatus.LOADED.code()), eq(FileStatus.ARCHIVED.code()), any()))
        .thenReturn(1);
    when(fileGovernanceRepository.operationDetail(anyString(), anyString(), any(), any()))
        .thenReturn(new FileGovernanceRepository.OperationDetailView(null, null, null, null));

    String result = service.archiveFile(cmd);

    assertThat(result).isEqualTo(FileStatus.ARCHIVED.code());
    // 成功审计写一次 (SUCCESS)，不写 FAILED
    ArgumentCaptor<FileGovernanceRepository.FileAuditCommand> captor =
        ArgumentCaptor.forClass(FileGovernanceRepository.FileAuditCommand.class);
    verify(fileGovernanceRepository, times(1)).appendAudit(captor.capture());
    assertThat(captor.getValue().operationResult()).isEqualTo("SUCCESS");
    assertThat(captor.getValue().operationType()).isEqualTo("ARCHIVE");
  }

  @Test
  @DisplayName("归档原因为空时正常完成流转, 不发生空指针")
  void shouldArchiveFile_whenReasonNull_withoutNpe() {
    // 回归:reason 为 null 时,changeFileStatus 内构造审计 detail 的 Map.of 曾 NPE,
    // 把干净的业务流程/错误掩盖成 500。见 DefaultFileGovernanceService#changeFileStatus。
    FileGovernanceCommand cmd = baseCommand().reason(null).build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("file_status", FileStatus.LOADED.code()));
    when(fileGovernanceRepository.countActivePipelineInstances("t1", 1L)).thenReturn(0L);
    when(fileGovernanceRepository.countPendingDispatchRecords("t1", 1L)).thenReturn(0L);
    when(fileGovernanceRepository.updateFileStatus(
            eq("t1"), eq(1L), eq(FileStatus.LOADED.code()), eq(FileStatus.ARCHIVED.code()), any()))
        .thenReturn(1);
    when(fileGovernanceRepository.operationDetail(anyString(), anyString(), any(), any()))
        .thenReturn(new FileGovernanceRepository.OperationDetailView(null, null, null, null));

    // arrange 完成,act + assert:不再抛 NPE,正常返回 ARCHIVED
    assertThat(service.archiveFile(cmd)).isEqualTo(FileStatus.ARCHIVED.code());
  }

  @Test
  @DisplayName("已归档文件允许删除时更新为已删除状态")
  void shouldDeleteFile_whenArchivedToDeletedTransitionAllowed() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("file_status", FileStatus.ARCHIVED.code()));
    when(fileGovernanceRepository.countActivePipelineInstances("t1", 1L)).thenReturn(0L);
    when(fileGovernanceRepository.countPendingDispatchRecords("t1", 1L)).thenReturn(0L);
    when(fileGovernanceRepository.updateFileStatus(
            eq("t1"), eq(1L), eq(FileStatus.ARCHIVED.code()), eq(FileStatus.DELETED.code()), any()))
        .thenReturn(1);
    when(fileGovernanceRepository.operationDetail(anyString(), anyString(), any(), any()))
        .thenReturn(new FileGovernanceRepository.OperationDetailView(null, null, null, null));

    String result = service.deleteFile(cmd);

    assertThat(result).isEqualTo(FileStatus.DELETED.code());
  }

  @Test
  @DisplayName("文件记录不存在时抛出业务异常, 且不写审计")
  void shouldThrowAndWriteFailedAudit_whenFileRecordMissing() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L)).thenReturn(Map.of());

    assertThatThrownBy(() -> service.archiveFile(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.NOT_FOUND);
    // 文件不存在时未进入 try → 不写审计
    verify(fileGovernanceRepository, never()).appendAudit(any());
  }

  @Test
  @DisplayName("仍有活跃流水线实例时归档抛出状态冲突, 并写失败审计")
  void shouldThrowStateConflictAndAuditFailure_whenActivePipelinesExist() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("file_status", FileStatus.LOADED.code()));
    when(fileGovernanceRepository.countActivePipelineInstances("t1", 1L)).thenReturn(2L);

    assertThatThrownBy(() -> service.archiveFile(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.STATE_CONFLICT);
    // 失败路径写 FAILED 审计
    ArgumentCaptor<FileGovernanceRepository.FileAuditCommand> captor =
        ArgumentCaptor.forClass(FileGovernanceRepository.FileAuditCommand.class);
    verify(fileGovernanceRepository).appendAudit(captor.capture());
    assertThat(captor.getValue().operationResult()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("仍有待处理派发记录时归档抛出异常并写失败审计")
  void shouldThrowAndAuditFailure_whenPendingDispatchesExist() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("file_status", FileStatus.LOADED.code()));
    when(fileGovernanceRepository.countActivePipelineInstances("t1", 1L)).thenReturn(0L);
    when(fileGovernanceRepository.countPendingDispatchRecords("t1", 1L)).thenReturn(3L);

    assertThatThrownBy(() -> service.archiveFile(cmd)).isInstanceOf(BizException.class);
    ArgumentCaptor<FileGovernanceRepository.FileAuditCommand> captor =
        ArgumentCaptor.forClass(FileGovernanceRepository.FileAuditCommand.class);
    verify(fileGovernanceRepository).appendAudit(captor.capture());
    assertThat(captor.getValue().operationResult()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("终态文件再归档被状态机拒绝, 抛出异常且写失败审计")
  void shouldThrowAndAuditFailure_whenStateMachineRejectsTransition() {
    // DELETED 是终态,不能转 ARCHIVED
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("file_status", FileStatus.DELETED.code()));
    when(fileGovernanceRepository.countActivePipelineInstances("t1", 1L)).thenReturn(0L);
    when(fileGovernanceRepository.countPendingDispatchRecords("t1", 1L)).thenReturn(0L);

    assertThatThrownBy(() -> service.archiveFile(cmd)).isInstanceOf(BizException.class);
    verify(fileGovernanceRepository, never())
        .updateFileStatus(anyString(), anyLong(), anyString(), anyString(), any());
    ArgumentCaptor<FileGovernanceRepository.FileAuditCommand> captor =
        ArgumentCaptor.forClass(FileGovernanceRepository.FileAuditCommand.class);
    verify(fileGovernanceRepository).appendAudit(captor.capture());
    assertThat(captor.getValue().operationResult()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("状态更新未命中任何行时归档抛出状态冲突并写失败审计")
  void shouldThrowStateConflictAndAuditFailure_whenUpdateReturnsZero() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("file_status", FileStatus.LOADED.code()));
    when(fileGovernanceRepository.countActivePipelineInstances("t1", 1L)).thenReturn(0L);
    when(fileGovernanceRepository.countPendingDispatchRecords("t1", 1L)).thenReturn(0L);
    when(fileGovernanceRepository.updateFileStatus(
            anyString(), anyLong(), anyString(), anyString(), any()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.archiveFile(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.STATE_CONFLICT);
    ArgumentCaptor<FileGovernanceRepository.FileAuditCommand> captor =
        ArgumentCaptor.forClass(FileGovernanceRepository.FileAuditCommand.class);
    verify(fileGovernanceRepository).appendAudit(captor.capture());
    assertThat(captor.getValue().operationResult()).isEqualTo("FAILED");
  }

  // ── presignFileDownload ──────────────────────────────────────────────────

  @Test
  @DisplayName("普通文件下载返回对象存储直连地址, 并记录审计")
  void shouldReturnPresignedUrl_forPlainFile() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of(
            "storage_bucket", "bucket-a",
            "storage_path", "path/to/file.csv"));
    when(fileGovernanceRepository.loadTemplateSecurityForFile("t1", 1L)).thenReturn(Map.of());
    when(s3GovernanceStorage.createPresignedDownloadUrl(
            eq("bucket-a"), eq("path/to/file.csv"), anyInt()))
        .thenReturn("https://objectStore/presigned/url");

    String url = service.presignFileDownload(cmd);

    assertThat(url).isEqualTo("https://objectStore/presigned/url");
    verify(fileGovernanceCommitService).appendAudit(any());
  }

  @Test
  @DisplayName("配置的下载地址有效期低于下限时按最短有效期生成")
  void shouldEnforceMinimum60sExpiry_whenPropConfiguredTooLow() {
    FileGovernanceCommand cmd = baseCommand().build();
    fileGovernanceProperties.getAccess().setPresignExpirySeconds(10); // 低于 60
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("storage_bucket", "b", "storage_path", "p"));
    when(fileGovernanceRepository.loadTemplateSecurityForFile("t1", 1L)).thenReturn(Map.of());
    when(s3GovernanceStorage.createPresignedDownloadUrl("b", "p", 60)).thenReturn("u");

    String url = service.presignFileDownload(cmd);

    assertThat(url).isEqualTo("u");
    verify(s3GovernanceStorage).createPresignedDownloadUrl("b", "p", 60);
  }

  @Test
  @DisplayName("开启内容加密时返回控制台代理地址, 不走对象存储直连")
  void shouldReturnConsoleProxyUrl_whenContentEncryptionEnabled() {
    FileGovernanceCommand cmd = baseCommand().approvalId("appr-1").build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("storage_bucket", "b", "storage_path", "p"));
    when(fileGovernanceRepository.loadTemplateSecurityForFile("t1", 1L))
        .thenReturn(Map.of(
            "content_encryption_enabled", true,
            "download_requires_approval", true,
            "encryption_key_ref", "kms-1"));

    String url = service.presignFileDownload(cmd);

    assertThat(url)
        .startsWith("/api/console/files/1/download?tenantId=t1")
        .contains("approvalId=appr-1");
    // 加密文件路径不调对象存储直连
    verify(s3GovernanceStorage, never()).createPresignedDownloadUrl(any(), any(), anyInt());
    verify(fileGovernanceCommitService).appendAudit(any());
  }

  @Test
  @DisplayName("文件记录不存在时生成下载地址抛出未找到异常")
  void shouldThrowNotFound_whenFileRecordMissingOnPresign() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L)).thenReturn(Map.of());
    assertThatThrownBy(() -> service.presignFileDownload(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.NOT_FOUND);
  }

  @Test
  @DisplayName("模板要求下载审批但审批缺失时抛出业务异常")
  void shouldThrowBusinessError_whenApprovalRequiredButMissing() {
    FileGovernanceCommand cmd = baseCommand().approvalId(null).build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("storage_bucket", "b", "storage_path", "p"));
    when(fileGovernanceRepository.loadTemplateSecurityForFile("t1", 1L))
        .thenReturn(Map.of("download_requires_approval", true));

    assertThatThrownBy(() -> service.presignFileDownload(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.BUSINESS_ERROR);
  }

  @Test
  @DisplayName("文件缺少存储路径时生成下载地址抛出状态冲突")
  void shouldThrowStateConflict_whenStoragePathMissing() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("storage_bucket", "b")); // 没 storage_path
    when(fileGovernanceRepository.loadTemplateSecurityForFile("t1", 1L)).thenReturn(Map.of());

    assertThatThrownBy(() -> service.presignFileDownload(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.STATE_CONFLICT);
  }

  @Test
  @DisplayName("开启绕过模式时忽略审批与加密要求, 直接走对象存储直连")
  void shouldBypassApprovalAndEncryption_whenBypassModeEnabled() {
    batchSecurityProperties.setBypassMode(true);
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("storage_bucket", "b", "storage_path", "p"));
    when(fileGovernanceRepository.loadTemplateSecurityForFile("t1", 1L))
        .thenReturn(Map.of(
            "content_encryption_enabled", true,
            "download_requires_approval", true));
    when(s3GovernanceStorage.createPresignedDownloadUrl(eq("b"), eq("p"), anyInt()))
        .thenReturn("https://direct-objectStore");

    String url = service.presignFileDownload(cmd);

    // bypass 时即使设了 encryption / approval,也走对象存储直连
    assertThat(url).isEqualTo("https://direct-objectStore");
  }

  // ── redispatchFile ───────────────────────────────────────────────────────

  @Test
  @DisplayName("相关资源均可解析时重置派发记录与分区任务, 并写成功审计")
  void shouldRedispatch_whenAllResourcesResolvable() {
    FileGovernanceCommand cmd = baseCommand().channelCode("CH1").build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L)).thenReturn(Map.of("id", 1L));
    when(fileGovernanceRepository.loadLatestDispatchRecord("t1", 1L, "CH1"))
        .thenReturn(Map.of("id", 100L, "pipeline_instance_id", 200L, "channel_code", "CH1"));
    when(fileGovernanceRepository.loadRelatedJobInstanceId(200L)).thenReturn(300L);

    JobInstanceEntity jobInstance = new JobInstanceEntity();
    jobInstance.setId(300L);
    when(jobInstanceMapper.selectById("t1", 300L)).thenReturn(jobInstance);

    JobTaskEntity task = new JobTaskEntity();
    task.setId(400L);
    task.setTenantId("t1");
    task.setJobPartitionId(500L);
    task.setTaskType("DISPATCH");
    task.setVersion(1L);
    task.setTaskSeq(1);
    JobTaskEntity nonDispatch = new JobTaskEntity();
    nonDispatch.setId(401L);
    nonDispatch.setTaskType("IMPORT");
    nonDispatch.setTaskSeq(0);
    when(jobTaskMapper.selectByQuery(any(JobTaskQuery.class)))
        .thenReturn(List.of(nonDispatch, task));

    JobPartitionEntity partition = new JobPartitionEntity();
    partition.setId(500L);
    partition.setVersion(2L);
    when(jobPartitionMapper.selectById("t1", 500L)).thenReturn(partition);

    String result = service.redispatchFile(cmd);

    assertThat(result).isEqualTo("REDISPATCH_ACCEPTED");
    verify(fileGovernanceRepository).resetDispatchRecordForRedispatch("t1", 100L);
    verify(jobPartitionMapper).resetForDispatch("t1", 500L, "READY", 2L);
    verify(jobTaskMapper).resetForRetry("t1", 400L, "READY", 1L);
    verify(taskDispatchOutboxService)
        .writeDispatchEvent(
            eq(jobInstance), eq(task), eq(partition), any(), anyString(), eq(RunMode.COMPENSATE));
    ArgumentCaptor<FileGovernanceRepository.FileAuditCommand> auditCaptor =
        ArgumentCaptor.forClass(FileGovernanceRepository.FileAuditCommand.class);
    verify(fileGovernanceRepository).appendAudit(auditCaptor.capture());
    assertThat(auditCaptor.getValue().operationType()).isEqualTo("REDISPATCH");
    assertThat(auditCaptor.getValue().operationResult()).isEqualTo("SUCCESS");
  }

  @Test
  @DisplayName("重新派发时文件记录不存在则抛出未找到异常")
  void shouldThrowNotFound_whenFileRecordMissingOnRedispatch() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L)).thenReturn(Map.of());
    assertThatThrownBy(() -> service.redispatchFile(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.NOT_FOUND);
  }

  @Test
  @DisplayName("重新派发时派发记录不存在则抛出未找到异常")
  void shouldThrowNotFound_whenDispatchRecordMissing() {
    FileGovernanceCommand cmd = baseCommand().channelCode("CH1").build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L)).thenReturn(Map.of("id", 1L));
    when(fileGovernanceRepository.loadLatestDispatchRecord("t1", 1L, "CH1")).thenReturn(Map.of());
    assertThatThrownBy(() -> service.redispatchFile(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.NOT_FOUND);
  }

  @Test
  @DisplayName("派发记录未关联作业实例时重新派发抛出状态冲突")
  void shouldThrowStateConflict_whenPipelineUnbound() {
    FileGovernanceCommand cmd = baseCommand().channelCode("CH1").build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L)).thenReturn(Map.of("id", 1L));
    when(fileGovernanceRepository.loadLatestDispatchRecord("t1", 1L, "CH1"))
        .thenReturn(Map.of("id", 100L, "pipeline_instance_id", 200L));
    when(fileGovernanceRepository.loadRelatedJobInstanceId(200L)).thenReturn(null);

    assertThatThrownBy(() -> service.redispatchFile(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.STATE_CONFLICT);
  }

  @Test
  @DisplayName("作业任务中没有派发任务时重新派发抛出未找到异常")
  void shouldThrowNotFound_whenNoDispatchTaskAmongJobTasks() {
    FileGovernanceCommand cmd = baseCommand().channelCode("CH1").build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L)).thenReturn(Map.of("id", 1L));
    when(fileGovernanceRepository.loadLatestDispatchRecord("t1", 1L, "CH1"))
        .thenReturn(Map.of("id", 100L, "pipeline_instance_id", 200L));
    when(fileGovernanceRepository.loadRelatedJobInstanceId(200L)).thenReturn(300L);

    JobInstanceEntity jobInstance = new JobInstanceEntity();
    jobInstance.setId(300L);
    when(jobInstanceMapper.selectById("t1", 300L)).thenReturn(jobInstance);

    JobTaskEntity nonDispatch = new JobTaskEntity();
    nonDispatch.setTaskType("IMPORT");
    when(jobTaskMapper.selectByQuery(any(JobTaskQuery.class))).thenReturn(List.of(nonDispatch));

    assertThatThrownBy(() -> service.redispatchFile(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.NOT_FOUND);
  }

  // ── operateArrivalGroup ──────────────────────────────────────────────────

  @Test
  @DisplayName("到达组下没有任何文件时抛出未找到异常")
  void shouldThrowNotFound_whenArrivalGroupHasNoFiles() {
    ArrivalGroupGovernanceCommand cmd = arrivalCmd("CONTINUE_WAITING");
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp")).thenReturn(List.of());
    assertThatThrownBy(() -> service.operateArrivalGroup(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.NOT_FOUND);
  }

  @Test
  @DisplayName("继续等待动作下更新组内全部文件并返回等待到达")
  void shouldReturnWaitingArrival_andUpdateAllFiles_whenContinueWaiting() {
    ArrivalGroupGovernanceCommand cmd = ArrivalGroupGovernanceCommand.builder()
        .tenantId("t1")
        .fileGroupCode("grp")
        .action("CONTINUE_WAITING")
        .operatorId("op-1")
        .traceId("tr")
        .reason("延期")
        .extendWaitSeconds(120L)
        .build();
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp"))
        .thenReturn(List.of(fileMap(11L, Map.of()), fileMap(12L, Map.of())));

    String state = service.operateArrivalGroup(cmd);

    assertThat(state).isEqualTo("WAITING_ARRIVAL");
    verify(fileGovernanceRepository).updateFileMetadata(eq("t1"), eq(11L), any());
    verify(fileGovernanceRepository).updateFileMetadata(eq("t1"), eq(12L), any());
    verify(fileGovernanceRepository, times(2)).appendAudit(any());
  }

  @Test
  @DisplayName("立即触发动作下返回已触发状态")
  void shouldReturnTriggered_whenTriggerNow() {
    ArrivalGroupGovernanceCommand cmd = arrivalCmd("TRIGGER_NOW");
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp"))
        .thenReturn(List.of(fileMap(11L, Map.of())));
    String state = service.operateArrivalGroup(cmd);
    assertThat(state).isEqualTo("TRIGGERED");
  }

  @Test
  @DisplayName("到达组跨营业日但未指定营业日时抛出状态冲突, 且不更新文件")
  void shouldThrowStateConflict_whenArrivalGroupSpansBizDatesWithoutBizDate() {
    ArrivalGroupGovernanceCommand cmd = arrivalCmd("TRIGGER_NOW");
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp"))
        .thenReturn(List.of(
            fileMap(11L, Map.of("biz_date", "2026-06-21")),
            fileMap(12L, Map.of("biz_date", "2026-06-20"))));

    assertThatThrownBy(() -> service.operateArrivalGroup(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.STATE_CONFLICT);
    verify(fileGovernanceRepository, never()).updateFileMetadata(anyString(), anyLong(), any());
  }

  @Test
  @DisplayName("指定营业日时按该营业日筛选组内文件并更新元数据")
  void shouldScopeArrivalGroupOperationByBizDateWhenProvided() {
    ArrivalGroupGovernanceCommand cmd = ArrivalGroupGovernanceCommand.builder()
        .tenantId("t1")
        .fileGroupCode("grp")
        .bizDate("2026-06-21")
        .action("TRIGGER_NOW")
        .operatorId("op")
        .traceId("tr")
        .reason("r")
        .build();
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp", "2026-06-21"))
        .thenReturn(List.of(fileMap(11L, Map.of("biz_date", "2026-06-21"))));

    String state = service.operateArrivalGroup(cmd);

    assertThat(state).isEqualTo("TRIGGERED");
    verify(fileGovernanceRepository).selectArrivalGroupFiles("t1", "grp", "2026-06-21");
    verify(fileGovernanceRepository, never()).selectArrivalGroupFiles("t1", "grp");
    verify(fileGovernanceRepository).updateFileMetadata(eq("t1"), eq(11L), any());
  }

  @Test
  @DisplayName("允许空跑时动作返回已触发状态")
  void shouldReturnTriggered_whenEmptyRunAllowed() {
    ArrivalGroupGovernanceCommand cmd = arrivalCmd("EMPTY_RUN");
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp"))
        .thenReturn(List.of(fileMap(11L, Map.of("allow_empty_run", true))));
    String state = service.operateArrivalGroup(cmd);
    assertThat(state).isEqualTo("TRIGGERED");
  }

  @Test
  @DisplayName("不允许空跑时动作抛出状态冲突")
  void shouldThrowStateConflict_whenEmptyRunNotAllowed() {
    ArrivalGroupGovernanceCommand cmd = arrivalCmd("EMPTY_RUN");
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp"))
        .thenReturn(List.of(fileMap(11L, Map.of("allow_empty_run", false))));
    assertThatThrownBy(() -> service.operateArrivalGroup(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.STATE_CONFLICT);
  }

  @Test
  @DisplayName("允许跳过批次时动作返回超时状态")
  void shouldReturnTimeout_whenSkipBatchAllowed() {
    ArrivalGroupGovernanceCommand cmd = arrivalCmd("SKIP_BATCH");
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp"))
        .thenReturn(List.of(fileMap(11L, Map.of("allow_skip_biz_date", true))));
    String state = service.operateArrivalGroup(cmd);
    assertThat(state).isEqualTo("TIMEOUT");
  }

  @Test
  @DisplayName("不允许跳过批次时动作抛出状态冲突")
  void shouldThrowStateConflict_whenSkipBatchNotAllowed() {
    ArrivalGroupGovernanceCommand cmd = arrivalCmd("SKIP_BATCH");
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp"))
        .thenReturn(List.of(fileMap(11L, Map.of("allow_skip_biz_date", false))));
    assertThatThrownBy(() -> service.operateArrivalGroup(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.STATE_CONFLICT);
  }

  @Test
  @DisplayName("不支持的动作被拒绝并抛出参数非法异常")
  void shouldThrowInvalidArgument_whenUnsupportedAction() {
    ArrivalGroupGovernanceCommand cmd = arrivalCmd("UNKNOWN");
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp"))
        .thenReturn(List.of(fileMap(11L, Map.of())));
    assertThatThrownBy(() -> service.operateArrivalGroup(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.INVALID_ARGUMENT);
  }

  @Test
  @DisplayName("组内文件缺少标识时跳过该文件, 只对有效文件写元数据与审计")
  void shouldSkipFilesWithoutId_whenIteratingArrivalGroup() {
    // 验证 toLong(null) 时 continue 分支
    ArrivalGroupGovernanceCommand cmd = arrivalCmd("CONTINUE_WAITING");
    FileGovernanceArrivalViews.ArrivalGroupFileView noId =
        FileGovernanceArrivalViews.arrivalGroupFile(new LinkedHashMap<>());
    FileGovernanceArrivalViews.ArrivalGroupFileView withId = fileMap(99L, Map.of());
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp"))
        .thenReturn(List.of(noId, withId));

    String state = service.operateArrivalGroup(cmd);

    assertThat(state).isEqualTo("WAITING_ARRIVAL");
    // 只对有 id 的写一次
    verify(fileGovernanceRepository, times(1)).updateFileMetadata(eq("t1"), eq(99L), any());
    verify(fileGovernanceRepository, times(1)).appendAudit(any());
  }

  @Test
  @DisplayName("延长等待秒数为空或零时使用默认延长值, 流程不中断")
  void shouldUseDefaultManualWaitExtension_whenExtendSecondsNullOrZero() {
    fileGovernanceProperties.getArrival().setManualWaitExtensionSeconds(999L);
    ArrivalGroupGovernanceCommand cmd = ArrivalGroupGovernanceCommand.builder()
        .tenantId("t1")
        .fileGroupCode("grp")
        .action("CONTINUE_WAITING")
        .operatorId("op")
        .extendWaitSeconds(0L)
        .build();
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp"))
        .thenReturn(List.of(fileMap(11L, Map.of())));

    String state = service.operateArrivalGroup(cmd);

    assertThat(state).isEqualTo("WAITING_ARRIVAL");
    // 没有崩,审计写一次即可
    verify(fileGovernanceRepository).appendAudit(any());
  }

  @Test
  @DisplayName("创建上传会话时登记对象存储记录, 路径含租户前缀且不含上级目录")
  void shouldCreateUploadSessionAsObjectStoreBackedRecord() {
    when(s3GovernanceStorage.defaultBucket()).thenReturn("bucket-a");
    when(fileGovernanceRepository.createReconciledFileRecord(any())).thenReturn(42L);
    FileUploadSessionCommand command = FileUploadSessionCommand.builder()
        .tenantId("t1")
        .channelCode("ch-1")
        .fileName("../中文 order.csv")
        .operatorId("op")
        .traceId("trace")
        .build();

    FileUploadSessionResponse response = service.createUploadSession(command);

    assertThat(response.fileId()).isEqualTo(42L);
    assertThat(response.uploadMode()).isEqualTo("APP_MANAGED");
    assertThat(response.uploadMethod()).isEqualTo("PUT");
    assertThat(response.uploadUrl()).isEqualTo("/api/console/files/42/content?tenantId=t1");
    ArgumentCaptor<FileGovernanceRepository.ReconciledFileRecordCommand> captor =
        ArgumentCaptor.forClass(FileGovernanceRepository.ReconciledFileRecordCommand.class);
    verify(fileGovernanceRepository).createReconciledFileRecord(captor.capture());
    assertThat(captor.getValue().storage().storageType()).isEqualTo("S3");
    assertThat(captor.getValue().storage().storageBucket()).isEqualTo("bucket-a");
    assertThat(captor.getValue().storage().storagePath()).startsWith("uploads/t1/");
    assertThat(captor.getValue().storage().storagePath()).doesNotContain("..");
    verify(fileGovernanceRepository).appendAudit(any());
  }

  @Test
  @DisplayName("文件已存在于对象存储时确认到达并回写文件大小")
  void shouldConfirmFileArrivalAfterObjectExists() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("storage_bucket", "bucket-a", "storage_path", "uploads/t1/a.csv"));
    when(s3GovernanceStorage.objectSize("bucket-a", "uploads/t1/a.csv")).thenReturn(123L);

    String result = service.confirmFileArrival(cmd);

    assertThat(result).isEqualTo("ARRIVAL_CONFIRMED");
    verify(fileGovernanceCommitService).confirmArrival(eq("t1"), eq(1L), eq(123L), any(), any());
  }

  @Test
  @DisplayName("对象存储中文件缺失时拒绝确认到达, 且不回写文件")
  void shouldRejectConfirmArrivalWhenContentMissing() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("storage_bucket", "bucket-a", "storage_path", "uploads/t1/a.csv"));
    when(s3GovernanceStorage.objectSize("bucket-a", "uploads/t1/a.csv"))
        .thenThrow(new ObjectNotFoundException("missing"));

    assertThatThrownBy(() -> service.confirmFileArrival(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.NOT_FOUND);
    verify(fileGovernanceRepository, never())
        .markFileArrivalConfirmed(any(), any(), anyLong(), any());
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private FileGovernanceCommand.FileGovernanceCommandBuilder baseCommand() {
    return FileGovernanceCommand.builder()
        .tenantId("t1")
        .fileId(1L)
        .operatorId("op-1")
        .traceId("trace-1")
        .reason("manual op");
  }

  private ArrivalGroupGovernanceCommand arrivalCmd(String action) {
    return ArrivalGroupGovernanceCommand.builder()
        .tenantId("t1")
        .fileGroupCode("grp")
        .action(action)
        .operatorId("op")
        .traceId("tr")
        .reason("r")
        .build();
  }

  private FileGovernanceArrivalViews.ArrivalGroupFileView fileMap(
      Long id, Map<String, Object> extra) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", id);
    map.putAll(extra);
    return FileGovernanceArrivalViews.arrivalGroupFile(map);
  }
}
