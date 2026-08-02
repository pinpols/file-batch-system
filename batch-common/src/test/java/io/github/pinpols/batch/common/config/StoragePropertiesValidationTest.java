package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class StoragePropertiesValidationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void rejectsInvalidS3EndpointAndTimeouts() {
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
  void allowsZeroFilesystemScanLimitAsExplicitUnlimitedMode() {
    FilesystemStorageProperties properties = new FilesystemStorageProperties();
    properties.setMaxListScanEntries(0);

    assertThat(validator.validate(properties)).isEmpty();
  }

  @Test
  void rejectsUnboundedInMemoryEncryptionConfiguration() {
    ObjectStoreEncryptionProperties properties = new ObjectStoreEncryptionProperties();
    properties.setMaxInMemoryEncryptBytes(0);

    assertThat(validator.validate(properties))
        .extracting(Object::toString)
        .anyMatch(message -> message.contains("max-in-memory-encrypt-bytes"));
  }
}
