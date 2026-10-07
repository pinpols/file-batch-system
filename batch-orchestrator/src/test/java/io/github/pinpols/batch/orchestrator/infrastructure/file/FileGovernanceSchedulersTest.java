package io.github.pinpols.batch.orchestrator.infrastructure.file;

import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("文件治理调度入口 - 校验各治理调度触发后均把执行委派给文件治理服务")
class FileGovernanceSchedulersTest {

  @Mock
  private FileGovernanceScheduler fileGovernanceScheduler;

  @Mock
  private OrchestratorGracefulShutdown gracefulShutdown;

  private FileGovernanceArchiveCleanupScheduler archiveCleanupScheduler;
  private FileGovernanceReconcileScheduler reconcileScheduler;
  private FileGovernanceArrivalGroupScheduler arrivalGroupScheduler;
  private FileGovernanceLatencyScheduler latencyScheduler;
  private UploadSessionCleanupScheduler uploadSessionCleanupScheduler;

  @BeforeEach
  void setUp() {
    archiveCleanupScheduler =
        new FileGovernanceArchiveCleanupScheduler(fileGovernanceScheduler, gracefulShutdown);
    reconcileScheduler =
        new FileGovernanceReconcileScheduler(fileGovernanceScheduler, gracefulShutdown);
    arrivalGroupScheduler =
        new FileGovernanceArrivalGroupScheduler(fileGovernanceScheduler, gracefulShutdown);
    latencyScheduler =
        new FileGovernanceLatencyScheduler(fileGovernanceScheduler, gracefulShutdown);
    uploadSessionCleanupScheduler =
        new UploadSessionCleanupScheduler(fileGovernanceScheduler, gracefulShutdown);
  }

  @Test
  @DisplayName("归档文件清理调度触发时委派治理服务执行归档清理")
  void shouldDelegateArchiveCleanup() {
    archiveCleanupScheduler.cleanupArchivedFiles();

    verify(fileGovernanceScheduler).cleanupArchivedFiles();
  }

  @Test
  @DisplayName("对象存储对账调度触发时委派治理服务执行对账")
  void shouldDelegateReconcile() {
    reconcileScheduler.reconcileObjectStorage();

    verify(fileGovernanceScheduler).reconcileObjectStorage();
  }

  @Test
  @DisplayName("文件到达分组管理调度触发时委派治理服务执行分组管理")
  void shouldDelegateArrivalGroupManagement() {
    arrivalGroupScheduler.manageFileArrivalGroups();

    verify(fileGovernanceScheduler).manageFileArrivalGroups();
  }

  @Test
  @DisplayName("孤儿上传会话清理调度触发时委派治理服务执行会话清理")
  void shouldDelegateOrphanUploadSessionCleanup() {
    uploadSessionCleanupScheduler.cleanupOrphanUploadSessions();

    verify(fileGovernanceScheduler).cleanupOrphanUploadSessions();
  }

  @Test
  @DisplayName("时延指标采集调度触发时委派治理服务执行指标采集")
  void shouldDelegateLatencyCollection() {
    latencyScheduler.collectLatencyMetrics();

    verify(fileGovernanceScheduler).collectLatencyMetrics();
  }
}
