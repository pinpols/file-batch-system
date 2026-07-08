package io.github.pinpols.batch.console.domain.file.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.console.domain.file.mapper.FileRecordMapper;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFileOperationResponse;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class DefaultConsoleFileApplicationServiceTest {

  private final OrchestratorInternalRestClient orchestratorClient =
      mock(OrchestratorInternalRestClient.class);
  private final OrchestratorApprovalClient approvalClient = mock(OrchestratorApprovalClient.class);
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
            approvalClient,
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
  void shouldCloseUploadInputStreamAfterStorePut() {
    when(fileRecordMapper.selectFileRecordById("t1", 1L))
        .thenReturn(
            Map.of(
                "storage_type", "S3",
                "storage_bucket", "bucket-a",
                "storage_path", "uploads/t1/a.csv"));
    CloseTrackingInputStream inputStream =
        new CloseTrackingInputStream("id,name\n1,A\n".getBytes());
    MultipartFile file = new CloseTrackingMultipartFile(inputStream, 12L, "text/csv");

    service.uploadContent("t1", 1L, file, "idem-1");

    assertThat(inputStream.closed).isTrue();
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

  private static final class CloseTrackingMultipartFile implements MultipartFile {
    private final CloseTrackingInputStream inputStream;
    private final long size;
    private final String contentType;

    private CloseTrackingMultipartFile(
        CloseTrackingInputStream inputStream, long size, String contentType) {
      this.inputStream = inputStream;
      this.size = size;
      this.contentType = contentType;
    }

    @Override
    public String getName() {
      return "file";
    }

    @Override
    public String getOriginalFilename() {
      return "a.csv";
    }

    @Override
    public String getContentType() {
      return contentType;
    }

    @Override
    public boolean isEmpty() {
      return size <= 0;
    }

    @Override
    public long getSize() {
      return size;
    }

    @Override
    public byte[] getBytes() {
      throw new UnsupportedOperationException();
    }

    @Override
    public InputStream getInputStream() {
      return inputStream;
    }

    @Override
    public void transferTo(File dest) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class CloseTrackingInputStream extends ByteArrayInputStream {
    private boolean closed;

    private CloseTrackingInputStream(byte[] buf) {
      super(buf);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
