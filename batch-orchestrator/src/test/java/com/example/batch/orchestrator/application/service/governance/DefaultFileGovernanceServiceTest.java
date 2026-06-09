package com.example.batch.orchestrator.application.service.governance;

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

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.RunMode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.storage.ObjectNotFoundException;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.config.FileGovernanceProperties;
import com.example.batch.orchestrator.domain.command.ArrivalGroupGovernanceCommand;
import com.example.batch.orchestrator.domain.command.FileGovernanceCommand;
import com.example.batch.orchestrator.domain.command.FileUploadSessionCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import com.example.batch.orchestrator.infrastructure.file.S3GovernanceStorage;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
class DefaultFileGovernanceServiceTest {

  @Mock private FileGovernanceRepository fileGovernanceRepository;
  @Mock private JobTaskMapper jobTaskMapper;
  @Mock private JobPartitionMapper jobPartitionMapper;
  @Mock private JobInstanceMapper jobInstanceMapper;
  @Mock private TaskDispatchOutboxService taskDispatchOutboxService;
  @Mock private S3GovernanceStorage s3GovernanceStorage;

  private final FileGovernanceProperties fileGovernanceProperties = new FileGovernanceProperties();
  private final BatchSecurityProperties batchSecurityProperties = new BatchSecurityProperties();

  private DefaultFileGovernanceService service;

  @BeforeEach
  void setUp() {
    service =
        new DefaultFileGovernanceService(
            fileGovernanceRepository,
            jobTaskMapper,
            jobPartitionMapper,
            jobInstanceMapper,
            taskDispatchOutboxService,
            fileGovernanceProperties,
            s3GovernanceStorage,
            batchSecurityProperties);
  }

  // ── validateCommand / validateArrivalGroupCommand ────────────────────────

  @Test
  void shouldThrow_whenTenantIdBlank_onArchive() {
    FileGovernanceCommand cmd = baseCommand().tenantId("").build();
    assertThatThrownBy(() -> service.archiveFile(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.INVALID_ARGUMENT);
  }

  @Test
  void shouldThrow_whenFileIdNull_onDelete() {
    FileGovernanceCommand cmd = baseCommand().fileId(null).build();
    assertThatThrownBy(() -> service.deleteFile(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldThrow_whenArrivalGroupCodeBlank() {
    ArrivalGroupGovernanceCommand cmd =
        ArrivalGroupGovernanceCommand.builder()
            .tenantId("t1")
            .fileGroupCode("")
            .action("CONTINUE_WAITING")
            .build();
    assertThatThrownBy(() -> service.operateArrivalGroup(cmd)).isInstanceOf(BizException.class);
  }

  @Test
  void shouldThrow_whenArrivalActionBlank() {
    ArrivalGroupGovernanceCommand cmd =
        ArrivalGroupGovernanceCommand.builder()
            .tenantId("t1")
            .fileGroupCode("grp")
            .action("")
            .build();
    assertThatThrownBy(() -> service.operateArrivalGroup(cmd)).isInstanceOf(BizException.class);
  }

  // ── archiveFile / deleteFile (changeFileStatus) ──────────────────────────

  @Test
  void shouldArchiveFile_whenStatusTransitionAllowed() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("file_status", "LOADED"));
    when(fileGovernanceRepository.countActivePipelineInstances("t1", 1L)).thenReturn(0L);
    when(fileGovernanceRepository.countPendingDispatchRecords("t1", 1L)).thenReturn(0L);
    when(fileGovernanceRepository.updateFileStatus(
            eq("t1"), eq(1L), eq("LOADED"), eq("ARCHIVED"), any()))
        .thenReturn(1);
    when(fileGovernanceRepository.operationDetail(anyString(), anyString(), any(), any()))
        .thenReturn(Map.of());

    String result = service.archiveFile(cmd);

    assertThat(result).isEqualTo("ARCHIVED");
    // 成功审计写一次 (SUCCESS)，不写 FAILED
    ArgumentCaptor<FileGovernanceRepository.FileAuditCommand> captor =
        ArgumentCaptor.forClass(FileGovernanceRepository.FileAuditCommand.class);
    verify(fileGovernanceRepository, times(1)).appendAudit(captor.capture());
    assertThat(captor.getValue().operationResult()).isEqualTo("SUCCESS");
    assertThat(captor.getValue().operationType()).isEqualTo("ARCHIVE");
  }

  @Test
  void shouldDeleteFile_whenArchivedToDeletedTransitionAllowed() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("file_status", "ARCHIVED"));
    when(fileGovernanceRepository.countActivePipelineInstances("t1", 1L)).thenReturn(0L);
    when(fileGovernanceRepository.countPendingDispatchRecords("t1", 1L)).thenReturn(0L);
    when(fileGovernanceRepository.updateFileStatus(
            eq("t1"), eq(1L), eq("ARCHIVED"), eq("DELETED"), any()))
        .thenReturn(1);
    when(fileGovernanceRepository.operationDetail(anyString(), anyString(), any(), any()))
        .thenReturn(Map.of());

    String result = service.deleteFile(cmd);

    assertThat(result).isEqualTo("DELETED");
  }

  @Test
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
  void shouldThrowStateConflictAndAuditFailure_whenActivePipelinesExist() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("file_status", "LOADED"));
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
  void shouldThrowAndAuditFailure_whenPendingDispatchesExist() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("file_status", "LOADED"));
    when(fileGovernanceRepository.countActivePipelineInstances("t1", 1L)).thenReturn(0L);
    when(fileGovernanceRepository.countPendingDispatchRecords("t1", 1L)).thenReturn(3L);

    assertThatThrownBy(() -> service.archiveFile(cmd)).isInstanceOf(BizException.class);
    ArgumentCaptor<FileGovernanceRepository.FileAuditCommand> captor =
        ArgumentCaptor.forClass(FileGovernanceRepository.FileAuditCommand.class);
    verify(fileGovernanceRepository).appendAudit(captor.capture());
    assertThat(captor.getValue().operationResult()).isEqualTo("FAILED");
  }

  @Test
  void shouldThrowAndAuditFailure_whenStateMachineRejectsTransition() {
    // DELETED 是终态,不能转 ARCHIVED
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("file_status", "DELETED"));
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
  void shouldThrowStateConflictAndAuditFailure_whenUpdateReturnsZero() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("file_status", "LOADED"));
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
  void shouldReturnPresignedUrl_forPlainFile() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(
            Map.of(
                "storage_bucket", "bucket-a",
                "storage_path", "path/to/file.csv"));
    when(fileGovernanceRepository.loadTemplateSecurityForFile("t1", 1L)).thenReturn(Map.of());
    when(s3GovernanceStorage.createPresignedDownloadUrl(
            eq("bucket-a"), eq("path/to/file.csv"), anyInt()))
        .thenReturn("https://objectStore/presigned/url");

    String url = service.presignFileDownload(cmd);

    assertThat(url).isEqualTo("https://objectStore/presigned/url");
    verify(fileGovernanceRepository).appendAudit(any());
  }

  @Test
  void shouldEnforceMinimum60sExpiry_whenPropConfiguredTooLow() {
    FileGovernanceCommand cmd = baseCommand().build();
    fileGovernanceProperties.getAccess().setPresignExpirySeconds(10); // 低于 60
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("storage_bucket", "b", "storage_path", "p"));
    when(fileGovernanceRepository.loadTemplateSecurityForFile("t1", 1L)).thenReturn(Map.of());
    when(s3GovernanceStorage.createPresignedDownloadUrl(eq("b"), eq("p"), eq(60))).thenReturn("u");

    String url = service.presignFileDownload(cmd);

    assertThat(url).isEqualTo("u");
    verify(s3GovernanceStorage).createPresignedDownloadUrl("b", "p", 60);
  }

  @Test
  void shouldReturnConsoleProxyUrl_whenContentEncryptionEnabled() {
    FileGovernanceCommand cmd = baseCommand().approvalId("appr-1").build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("storage_bucket", "b", "storage_path", "p"));
    when(fileGovernanceRepository.loadTemplateSecurityForFile("t1", 1L))
        .thenReturn(
            Map.of(
                "content_encryption_enabled", true,
                "download_requires_approval", true,
                "encryption_key_ref", "kms-1"));

    String url = service.presignFileDownload(cmd);

    assertThat(url)
        .startsWith("/api/console/files/1/download?tenantId=t1")
        .contains("approvalId=appr-1");
    // 加密文件路径不调 MinIO 直连
    verify(s3GovernanceStorage, never()).createPresignedDownloadUrl(any(), any(), anyInt());
    verify(fileGovernanceRepository).appendAudit(any());
  }

  @Test
  void shouldThrowNotFound_whenFileRecordMissingOnPresign() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L)).thenReturn(Map.of());
    assertThatThrownBy(() -> service.presignFileDownload(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.NOT_FOUND);
  }

  @Test
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
  void shouldBypassApprovalAndEncryption_whenBypassModeEnabled() {
    batchSecurityProperties.setBypassMode(true);
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("storage_bucket", "b", "storage_path", "p"));
    when(fileGovernanceRepository.loadTemplateSecurityForFile("t1", 1L))
        .thenReturn(
            Map.of(
                "content_encryption_enabled", true,
                "download_requires_approval", true));
    when(s3GovernanceStorage.createPresignedDownloadUrl(eq("b"), eq("p"), anyInt()))
        .thenReturn("https://direct-objectStore");

    String url = service.presignFileDownload(cmd);

    // bypass 时即使设了 encryption / approval,也走直连 MinIO
    assertThat(url).isEqualTo("https://direct-objectStore");
  }

  // ── redispatchFile ───────────────────────────────────────────────────────

  @Test
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
  void shouldThrowNotFound_whenFileRecordMissingOnRedispatch() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L)).thenReturn(Map.of());
    assertThatThrownBy(() -> service.redispatchFile(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.NOT_FOUND);
  }

  @Test
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
  void shouldThrowNotFound_whenArrivalGroupHasNoFiles() {
    ArrivalGroupGovernanceCommand cmd = arrivalCmd("CONTINUE_WAITING");
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp")).thenReturn(List.of());
    assertThatThrownBy(() -> service.operateArrivalGroup(cmd))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.NOT_FOUND);
  }

  @Test
  void shouldReturnWaitingArrival_andUpdateAllFiles_whenContinueWaiting() {
    ArrivalGroupGovernanceCommand cmd =
        ArrivalGroupGovernanceCommand.builder()
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
  void shouldReturnTriggered_whenTriggerNow() {
    ArrivalGroupGovernanceCommand cmd = arrivalCmd("TRIGGER_NOW");
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp"))
        .thenReturn(List.of(fileMap(11L, Map.of())));
    String state = service.operateArrivalGroup(cmd);
    assertThat(state).isEqualTo("TRIGGERED");
  }

  @Test
  void shouldReturnTriggered_whenEmptyRunAllowed() {
    ArrivalGroupGovernanceCommand cmd = arrivalCmd("EMPTY_RUN");
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp"))
        .thenReturn(List.of(fileMap(11L, Map.of("allow_empty_run", true))));
    String state = service.operateArrivalGroup(cmd);
    assertThat(state).isEqualTo("TRIGGERED");
  }

  @Test
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
  void shouldReturnTimeout_whenSkipBatchAllowed() {
    ArrivalGroupGovernanceCommand cmd = arrivalCmd("SKIP_BATCH");
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp"))
        .thenReturn(List.of(fileMap(11L, Map.of("allow_skip_biz_date", true))));
    String state = service.operateArrivalGroup(cmd);
    assertThat(state).isEqualTo("TIMEOUT");
  }

  @Test
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
  void shouldSkipFilesWithoutId_whenIteratingArrivalGroup() {
    // 验证 toLong(null) 时 continue 分支
    ArrivalGroupGovernanceCommand cmd = arrivalCmd("CONTINUE_WAITING");
    Map<String, Object> noId = new LinkedHashMap<>();
    Map<String, Object> withId = fileMap(99L, Map.of());
    when(fileGovernanceRepository.selectArrivalGroupFiles("t1", "grp"))
        .thenReturn(List.of(noId, withId));

    String state = service.operateArrivalGroup(cmd);

    assertThat(state).isEqualTo("WAITING_ARRIVAL");
    // 只对有 id 的写一次
    verify(fileGovernanceRepository, times(1)).updateFileMetadata(eq("t1"), eq(99L), any());
    verify(fileGovernanceRepository, times(1)).appendAudit(any());
  }

  @Test
  void shouldUseDefaultManualWaitExtension_whenExtendSecondsNullOrZero() {
    fileGovernanceProperties.getArrival().setManualWaitExtensionSeconds(999L);
    ArrivalGroupGovernanceCommand cmd =
        ArrivalGroupGovernanceCommand.builder()
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
  void shouldCreateUploadSessionAsObjectStoreBackedRecord() {
    when(s3GovernanceStorage.defaultBucket()).thenReturn("bucket-a");
    when(fileGovernanceRepository.createReconciledFileRecord(any())).thenReturn(42L);
    FileUploadSessionCommand command =
        FileUploadSessionCommand.builder()
            .tenantId("t1")
            .channelCode("ch-1")
            .fileName("../中文 order.csv")
            .operatorId("op")
            .traceId("trace")
            .build();

    Map<String, Object> response = service.createUploadSession(command);

    assertThat(response.get("fileId")).isEqualTo(42L);
    assertThat(response.get("uploadMode")).isEqualTo("APP_MANAGED");
    assertThat(response.get("uploadMethod")).isEqualTo("PUT");
    assertThat(response.get("uploadUrl")).isEqualTo("/api/console/files/42/content?tenantId=t1");
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
  void shouldConfirmFileArrivalAfterObjectExists() {
    FileGovernanceCommand cmd = baseCommand().build();
    when(fileGovernanceRepository.loadFileRecord("t1", 1L))
        .thenReturn(Map.of("storage_bucket", "bucket-a", "storage_path", "uploads/t1/a.csv"));
    when(s3GovernanceStorage.objectSize("bucket-a", "uploads/t1/a.csv")).thenReturn(123L);

    String result = service.confirmFileArrival(cmd);

    assertThat(result).isEqualTo("ARRIVAL_CONFIRMED");
    verify(fileGovernanceRepository).markFileArrivalConfirmed(eq("t1"), eq(1L), eq(123L), any());
    verify(fileGovernanceRepository).appendAudit(any());
  }

  @Test
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

  private Map<String, Object> fileMap(Long id, Map<String, Object> extra) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", id);
    map.putAll(extra);
    return map;
  }
}
