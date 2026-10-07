package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.storage.ObjectStoreException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("分发文件内容读取:本地文件读取、路径穿越防护与对象存储缺失时的失败语义")
class DispatchFileContentResolverPathTest {

  @TempDir
  Path tempDir;

  @Mock
  private S3StorageProperties s3Properties;

  @Mock
  private BatchObjectCryptoService cryptoService;

  @Mock
  private ObjectProvider<BatchObjectStore> objectStoreProvider;

  private DispatchFileContentResolver resolver;

  @BeforeEach
  void setUp() throws Exception {
    // 未配对象存储:provider.getIfAvailable() 默认返回 null → objectStore 为 null(测 LOCAL 路径)。
    resolver = new DispatchFileContentResolver(s3Properties, cryptoService, objectStoreProvider);
    var init = DispatchFileContentResolver.class.getDeclaredMethod("init");
    init.setAccessible(true);
    init.invoke(resolver);
  }

  @Test
  @DisplayName("本地存储文件按路径读取,读出的内容与写入时一致")
  void openInputStream_localFile_returnsContent() throws Exception {
    Path file = tempDir.resolve("test.csv");
    Files.writeString(file, "col1,col2\nval1,val2", StandardCharsets.UTF_8);

    Map<String, Object> fileRecord =
        Map.of("storage_type", "LOCAL", "storage_path", file.toString());
    try (InputStream in = resolver.openInputStream(fileRecord)) {
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(content).contains("col1,col2");
    }
  }

  @Test
  @DisplayName("存储路径含上级目录穿越片段时,读取被安全异常拒绝")
  void openInputStream_pathWithDotDot_throwsSecurity() {
    Map<String, Object> fileRecord = Map.of(
        "storage_type", "LOCAL",
        "storage_path", "/tmp/../etc/passwd");
    assertThatThrownBy(() -> resolver.openInputStream(fileRecord))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("..");
  }

  @Test
  @DisplayName("本地存储记录缺少存储路径时,读取以非法状态异常失败")
  void openInputStream_missingStoragePath_throwsIllegalState() {
    Map<String, Object> fileRecord = Map.of("storage_type", "LOCAL");
    assertThatThrownBy(() -> resolver.openInputStream(fileRecord))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("storage_path");
  }

  @Test
  @DisplayName("远端存储记录但对象存储未配置时,读取以对象存储异常失败")
  void openInputStream_remoteWithoutObjectStore_throwsObjectStoreException() {
    Map<String, Object> fileRecord =
        Map.of("storage_type", "OSS", "storage_path", "bucket/file.csv");
    assertThatThrownBy(() -> resolver.openInputStream(fileRecord))
        .isInstanceOf(ObjectStoreException.class)
        .hasMessageContaining("object store not configured");
  }
}
