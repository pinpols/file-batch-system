package com.example.batch.worker.processes.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.infrastructure.WorkerStartupAuditContributor.WorkerStartupAuditResult;
import com.example.batch.worker.processes.mapper.business.ProcessStagingMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProcessStagingStartupAuditContributorTest {

  @Test
  void auditWarnsWhenCleanupDisabledAndRowsPastRetentionExist() {
    ProcessStagingMapper mapper = mock(ProcessStagingMapper.class);
    when(mapper.selectMinStagedAt()).thenReturn(Instant.now().minusSeconds(7200));
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
