package io.github.pinpols.batch.testing;

import io.github.pinpols.batch.common.utils.EmptyChecks;

/** S3 兼容对象存储测试端点统一工厂，避免测试直接绑定具体实现。 */
public final class TestObjectStoreContainers {

  private TestObjectStoreContainers() {}

  /** 兼容少量直接验证 S3ObjectStore 的老测试：仍返回本地容器实现。 */
  public static MinioObjectStoreContainer create() {
    return new MinioObjectStoreContainer();
  }

  static TestObjectStoreEndpoint createEndpoint() {
    String provider = System.getProperty("batch.test.storage.provider", "minio").trim();
    if ("external".equalsIgnoreCase(provider)) {
      return externalEndpoint();
    }
    if (!"minio".equalsIgnoreCase(provider)) {
      throw new IllegalArgumentException(
          "unsupported batch.test.storage.provider: " + provider + " (supported: minio, external)");
    }
    return create();
  }

  private static TestObjectStoreEndpoint externalEndpoint() {
    return new ExternalObjectStoreEndpoint(
        requiredProperty("batch.test.storage.endpoint"),
        requiredProperty("batch.test.storage.access-key"),
        requiredProperty("batch.test.storage.secret-key"),
        System.getProperty("batch.test.storage.bucket", "batch-test"),
        System.getProperty("batch.test.storage.region", "us-east-1"),
        Boolean.parseBoolean(System.getProperty("batch.test.storage.path-style-enabled", "true")));
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (EmptyChecks.isBlank(value)) {
      throw new IllegalArgumentException("required system property is not set: " + name);
    }
    return value.trim();
  }

  private record ExternalObjectStoreEndpoint(
      String endpoint,
      String accessKey,
      String secretKey,
      String defaultBucket,
      String region,
      boolean pathStyleEnabled)
      implements TestObjectStoreEndpoint {

    @Override
    public String getEndpoint() {
      return endpoint;
    }

    @Override
    public String getAccessKey() {
      return accessKey;
    }

    @Override
    public String getSecretKey() {
      return secretKey;
    }

    @Override
    public String getDefaultBucket() {
      return defaultBucket;
    }

    @Override
    public String getRegion() {
      return region;
    }

    @Override
    public boolean isPathStyleEnabled() {
      return pathStyleEnabled;
    }
  }
}
