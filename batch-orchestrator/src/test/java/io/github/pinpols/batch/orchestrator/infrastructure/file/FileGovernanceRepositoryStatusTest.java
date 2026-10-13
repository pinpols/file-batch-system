package io.github.pinpols.batch.orchestrator.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.orchestrator.mapper.FileGovernanceMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("文件治理仓储:重新派送恢复创建态与未回执态")
class FileGovernanceRepositoryStatusTest {

  @Test
  @DisplayName("重新派送参数包含 CREATED 与 NONE 状态")
  void shouldResetDispatchAndReceiptStatuses() {
    FileGovernanceMapper mapper = mock(FileGovernanceMapper.class);
    FileGovernanceRepository repository = new FileGovernanceRepository(mapper);
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.captor();
    doReturn(1).when(mapper).resetDispatchRecordForRedispatch(any());

    repository.resetDispatchRecordForRedispatch("tenant-a", 7L);

    verify(mapper).resetDispatchRecordForRedispatch(captor.capture());
    assertThat(captor.getValue())
        .containsEntry("tenantId", "tenant-a")
        .containsEntry("dispatchRecordId", 7L)
        .containsEntry("dispatchCreatedStatus", "CREATED")
        .containsEntry("receiptNoneStatus", "NONE");
  }
}
