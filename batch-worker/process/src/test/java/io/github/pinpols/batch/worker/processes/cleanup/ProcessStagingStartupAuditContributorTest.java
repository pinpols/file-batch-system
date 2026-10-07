package io.github.pinpols.batch.worker.processes.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.worker.core.infrastructure.WorkerStartupAuditContributor.WorkerStartupAuditResult;
import io.github.pinpols.batch.worker.processes.mapper.business.ProcessStagingMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("处理暂存区启动审计:清理开关关闭且存在超过保留期的暂存行时给出不健康告警")
class ProcessStagingStartupAuditContributorTest {

  @Test
  @DisplayName("清理开关关闭且存在超过保留期的暂存行时,审计判不健康并在明细给出超期行数")
  void shouldWarn_whenCleanupDisabledAndRowsPastRetentionExist() {
    ProcessStagingMapper mapper = mock(ProcessStagingMapper.class);
    when(mapper.selectMinStagedAt()).thenReturn(BatchDateTimeSupport.utcNow().minusSeconds(7200));
    when(mapper.countOrphansOlderThan(24)).thenReturn(3L);
    ProcessStagingCleanupProperties properties = new ProcessStagingCleanupProperties();
    properties.setEnabled(false);
    ProcessStagingStartupAuditContributor contributor =
        new ProcessStagingStartupAuditContributor(mapper, properties);

    WorkerStartupAuditResult result = contributor.audit();

    assertThat(result.healthy()).isFalse();
    assertThat(result.details()).containsEntry("orphanRowsPastRetention", 3L);
  }
}
