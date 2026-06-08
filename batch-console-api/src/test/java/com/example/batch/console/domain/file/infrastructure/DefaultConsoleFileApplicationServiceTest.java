package com.example.batch.console.domain.file.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.S3StorageProperties;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.storage.BatchObjectStore;
import com.example.batch.console.domain.file.mapper.FileRecordMapper;
import com.example.batch.console.domain.file.web.response.ConsoleFileOperationResponse;
import com.example.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class DefaultConsoleFileApplicationServiceTest {

  private final OrchestratorInternalRestClient orchestratorClient =
      mock(OrchestratorInternalRestClient.class);
  private final ConsoleRequestMetadataResolver metadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
  private final FileRecordMapper fileRecordMapper = mock(FileRecordMapper.class);
  private final BatchObjectStore objectStore = mock(BatchObjectStore.class);
  private final S3StorageProperties s3Properties = new S3StorageProperties();
  private DefaultConsoleFileApplicationService service;

  @BeforeEach
  void setUp() {
    s3Properties.setBucket("default-bucket");
    service =
        new DefaultConsoleFileApplicationService(
            orchestratorClient,
            metadataResolver,
            tenantGuard,
            fileRecordMapper,
            objectStore,
            s3Properties);
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
  }

  @Test
  void shouldWriteUploadedContentToObjectStore() {
    when(fileRecordMapper.selectFileRecordById("t1", 1L))
        .thenReturn(
            Map.of(
                "storage_type", "S3",
                "storage_bucket", "bucket-a",
                "storage_path", "uploads/t1/a.csv",
                "mime_type", "text/csv"));
    MockMultipartFile file =
        new MockMultipartFile("file", "a.csv", "text/csv", "id,name\n1,A\n".getBytes());

    ConsoleFileOperationResponse response = service.uploadContent("t1", 1L, file, "idem-1");

    assertThat(response.status()).isEqualTo("UPLOADED");
    verify(objectStore)
        .put(
            eq("bucket-a"),
            eq("uploads/t1/a.csv"),
            any(InputStream.class),
            eq(12L),
            eq("text/csv"));
  }

  @Test
  void shouldRejectLocalPathFileRecordForUploadContent() {
    when(fileRecordMapper.selectFileRecordById("t1", 1L))
        .thenReturn(Map.of("storage_type", "LOCAL", "storage_path", "/tmp/a.csv"));
    MockMultipartFile file =
        new MockMultipartFile("file", "a.csv", "text/csv", "id,name\n1,A\n".getBytes());

    assertThatThrownBy(() -> service.uploadContent("t1", 1L, file, "idem-1"))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.STATE_CONFLICT);
  }
}
