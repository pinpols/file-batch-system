package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("存储属性校验:对象存储端点与超时边界、文件系统扫描上限的显式无限制模式,以及内存加密上限的拒绝规则")
class StoragePropertiesValidationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  @DisplayName("对象存储端点为空白、桶名为空、连接与读取超时非正、分片阈值与分片大小非正时逐项给出校验失败")
  void shouldRejectInvalidEndpointAndTimeoutBounds_whenObjectStorageMisconfigured() {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setEndpoint(" ");
    properties.setBucket("");
    properties.setConnectTimeoutMs(0);
    properties.setReadTimeoutMs(-1);
    properties.setMultipartThresholdBytes(0);
    properties.setMultipartPartSizeBytes(0);

    assertThat(validator.validate(properties))
        .extracting(Object::toString)
        .anyMatch(message -> message.contains("endpoint"))
        .anyMatch(message -> message.contains("bucket"))
        .anyMatch(message -> message.contains("connect-timeout-ms"))
        .anyMatch(message -> message.contains("read-timeout-ms"))
        .anyMatch(message -> message.contains("multipart-threshold-bytes"))
        .anyMatch(message -> message.contains("multipart-part-size-bytes"));
  }

  @Test
  @DisplayName("文件系统扫描条数上限设为零表示不做限制,校验结果为空")
  void shouldAllowZeroScanLimit_whenFilesystemUnlimitedModeRequested() {
    FilesystemStorageProperties properties = new FilesystemStorageProperties();
    properties.setMaxListScanEntries(0);

    assertThat(validator.validate(properties)).isEmpty();
  }

  @Test
  @DisplayName("内存加密字节上限设为零时给出校验失败提示,禁止无上限占用内存")
  void shouldRejectZeroInMemoryEncryptionLimit_whenEncryptionMisconfigured() {
    ObjectStoreEncryptionProperties properties = new ObjectStoreEncryptionProperties();
    properties.setMaxInMemoryEncryptBytes(0);

    assertThat(validator.validate(properties))
        .extracting(Object::toString)
        .anyMatch(message -> message.contains("max-in-memory-encrypt-bytes"));
  }
}
