package com.example.batch.worker.dispatchs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.config.S3StorageProperties;
import com.example.batch.common.service.BatchObjectCryptoService;
import io.minio.MinioClient;
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
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DispatchFileContentResolverPathTest {

  @TempDir Path tempDir;

  @Mock private S3StorageProperties minioProperties;

  @Mock private BatchObjectCryptoService cryptoService;

  @Mock private ObjectProvider<MinioClient> minioClientProvider;

  private DispatchFileContentResolver resolver;

  @BeforeEach
  void setUp() throws Exception {
    // 未配 MinIO:provider.getIfAvailable() 默认返回 null → minioClient 为 null(测 LOCAL 路径)。
    resolver = new DispatchFileContentResolver(minioProperties, cryptoService, minioClientProvider);
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
