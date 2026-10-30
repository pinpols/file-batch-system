package io.github.pinpols.batch.orchestrator.infrastructure.file;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.orchestrator.mapper.FileGovernanceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件治理仓储:重新派送保留失败态与未回执态")
class FileGovernanceRepositoryStatusTest {

  @Test
  @DisplayName("重新派送参数包含 FAILED 与 NONE 状态")
  void shouldResetDispatchAndReceiptStatuses() {
    FileGovernanceMapper mapper = mock(FileGovernanceMapper.class);
    FileGovernanceRepository repository = new FileGovernanceRepository(mapper);
    doReturn(1).when(mapper).resetDispatchRecordForRedispatch(any(), any(), any(), any());

    repository.resetDispatchRecordForRedispatch("tenant-a", 7L);

    verify(mapper).resetDispatchRecordForRedispatch("tenant-a", 7L, "FAILED", "NONE");
  }
}
