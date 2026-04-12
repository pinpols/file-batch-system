package com.example.batch.worker.dispatchs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.common.service.BatchObjectCryptoService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DispatchFileContentResolverPathTest {

  @TempDir Path tempDir;

  @Mock private MinioStorageProperties minioProperties;

  @Mock private BatchObjectCryptoService cryptoService;

  private DispatchFileContentResolver resolver;

  @BeforeEach
  void setUp() throws Exception {
    when(minioProperties.getEndpoint()).thenReturn(null);
    resolver = new DispatchFileContentResolver(minioProperties, cryptoService);
    // call PostConstruct manually (endpoint is blank, so minioClient stays null)
    var init = DispatchFileContentResolver.class.getDeclaredMethod("init");
    init.setAccessible(true);
    init.invoke(resolver);
  }

  @Test
  void openInputStream_localFile_returnsContent() throws Exception {
    Path file = tempDir.resolve("test.csv");
    Files.writeString(file, "col1,col2\nval1,val2", StandardCharsets.UTF_8);

    Map<String, Object> record = Map.of("storage_type", "LOCAL", "storage_path", file.toString());
    try (InputStream in = resolver.openInputStream(record)) {
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(content).contains("col1,col2");
    }
  }

  @Test
  void openInputStream_pathWithDotDot_throwsSecurity() {
    Map<String, Object> record =
        Map.of(
            "storage_type", "LOCAL",
            "storage_path", "/tmp/../etc/passwd");
    assertThatThrownBy(() -> resolver.openInputStream(record))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("..");
  }

  @Test
  void openInputStream_missingStoragePath_throwsIllegalState() {
    Map<String, Object> record = Map.of("storage_type", "LOCAL");
    assertThatThrownBy(() -> resolver.openInputStream(record))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("storage_path");
  }

  @Test
  void openInputStream_remoteWithoutMinioConfig_throwsIllegalState() {
    Map<String, Object> record = Map.of("storage_type", "OSS", "storage_path", "bucket/file.csv");
    assertThatThrownBy(() -> resolver.openInputStream(record))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MinIO");
  }
}
