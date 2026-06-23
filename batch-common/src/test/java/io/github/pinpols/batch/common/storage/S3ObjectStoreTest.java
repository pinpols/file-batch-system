package io.github.pinpols.batch.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.testing.ObjectStoreContainer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * {@link S3ObjectStore} 契约测试：用 Testcontainers MinIO 跑 put/get/getFrom/statSize/exists/list/delete
 * round-trip。Docker 不可用时整体跳过（不致编译/构建失败）。异常映射的纯单元覆盖见 {@link S3ObjectStoreExceptionMappingTest}。
 */
class S3ObjectStoreTest {

  private static ObjectStoreContainer objectStore;
  private static S3ObjectStore store;
  private static String bucket;

  @BeforeAll
  static void startMinio() {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker unavailable; skipping MinIO IT");
    objectStore = new ObjectStoreContainer();
    objectStore.start();
    bucket = objectStore.getDefaultBucket();

    StaticCredentialsProvider credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(objectStore.getAccessKey(), objectStore.getSecretKey()));
    S3Client s3Client =
        S3Client.builder()
            .endpointOverride(URI.create(objectStore.getEndpoint()))
            .credentialsProvider(credentials)
            .forcePathStyle(true)
            .region(Region.US_EAST_1)
            .build();
    S3Presigner presigner =
        S3Presigner.builder()
            .endpointOverride(URI.create(objectStore.getEndpoint()))
            .credentialsProvider(credentials)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .region(Region.US_EAST_1)
            .build();
    S3StorageProperties properties = new S3StorageProperties();
    properties.setEndpoint(objectStore.getEndpoint());
    properties.setAccessKey(objectStore.getAccessKey());
    properties.setSecretKey(objectStore.getSecretKey());
    properties.setBucket(bucket);
    properties.setAutoCreateBucket(true);
    store = new S3ObjectStore(s3Client, presigner, properties);
  }

  @AfterAll
  static void stopMinio() {
    if (objectStore != null) {
      objectStore.stop();
    }
  }

  @Test
  void shouldRoundTripPutGetStatExistsDelete() throws Exception {
    // 准备
    String key = "store/roundtrip.txt";
    byte[] content = "hello object store".getBytes(StandardCharsets.UTF_8);

    // 执行
    store.put(bucket, key, new ByteArrayInputStream(content), content.length, "text/plain");

    // 断言
    assertThat(store.exists(bucket, key)).isTrue();
    assertThat(store.statSize(bucket, key)).isEqualTo(content.length);
    try (InputStream in = store.get(bucket, key)) {
      assertThat(in.readAllBytes()).isEqualTo(content);
    }

    store.delete(bucket, key);
    assertThat(store.exists(bucket, key)).isFalse();
  }

  @Test
  void shouldBatchDeleteMany() {
    String prefix = "store/batchdelete/";
    for (int i = 0; i < 5; i++) {
      byte[] body = ("d" + i).getBytes(StandardCharsets.UTF_8);
      store.put(
          bucket, prefix + "k" + i, new ByteArrayInputStream(body), body.length, "text/plain");
    }
    List<String> keys =
        List.of(prefix + "k0", prefix + "k1", prefix + "k2", prefix + "k3", prefix + "k4");

    store.deleteMany(bucket, keys);

    for (String k : keys) {
      assertThat(store.exists(bucket, k)).isFalse();
    }
    // 空/null 是 no-op,不报错
    store.deleteMany(bucket, List.of());
    store.deleteMany(bucket, null);
  }

  @Test
  void shouldGenerateGetAndPutPresignUrls() {
    String key = "store/presign.txt";
    assertThat(store.supportsPresignPut()).isTrue();
    String getUrl = store.presign(bucket, key, Duration.ofMinutes(5));
    String putUrl = store.presignPut(bucket, key, Duration.ofMinutes(5), "text/plain");
    assertThat(getUrl).startsWith("http").contains(bucket);
    assertThat(putUrl).startsWith("http").contains(bucket);
    assertThat(putUrl).isNotEqualTo(getUrl);
  }

  @Test
  void shouldRejectExtraBytesBeyondDeclaredSize() {
    String key = "store/under-reported.txt";
    byte[] content = "payload-with-extra-bytes".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(
            () -> store.put(bucket, key, new ByteArrayInputStream(content), 7, "text/plain"))
        .isInstanceOf(ObjectStoreException.class)
        .hasMessageContaining("length mismatch");
  }

  @Test
  void shouldReadFromOffset() throws Exception {
    String key = "store/offset.txt";
    byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
    store.put(bucket, key, new ByteArrayInputStream(content), content.length, "text/plain");

    try (InputStream in = store.getFrom(bucket, key, 4)) {
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("456789");
    }
  }

  @Test
  void shouldListWithPaginationMarker() {
    String prefix = "store/list/";
    for (int i = 0; i < 5; i++) {
      byte[] body = ("item" + i).getBytes(StandardCharsets.UTF_8);
      store.put(
          bucket, prefix + "k" + i, new ByteArrayInputStream(body), body.length, "text/plain");
    }

    ObjectListing firstPage = store.list(bucket, prefix, null, 2);
    assertThat(firstPage.objects()).hasSize(2);
    assertThat(firstPage.nextMarker()).isEqualTo(prefix + "k1");

    ObjectListing secondPage = store.list(bucket, prefix, firstPage.nextMarker(), 2);
    assertThat(secondPage.objects()).hasSize(2);
    assertThat(secondPage.objects().get(0).key()).isEqualTo(prefix + "k2");

    ObjectListing lastPage = store.list(bucket, prefix, secondPage.nextMarker(), 2);
    assertThat(lastPage.objects()).hasSize(1);
    assertThat(lastPage.nextMarker()).isNull();
  }

  @Test
  void shouldThrowObjectNotFoundForMissingStat() {
    assertThatThrownBy(() -> store.statSize(bucket, "store/no-such-key.txt"))
        .isInstanceOf(ObjectNotFoundException.class);
  }

  @Test
  void shouldPresignDownloadUrl() {
    String key = "store/presign.txt";
    byte[] body = "presigned".getBytes(StandardCharsets.UTF_8);
    store.put(bucket, key, new ByteArrayInputStream(body), body.length, "text/plain");

    String url = store.presign(bucket, key, Duration.ofMinutes(5));

    assertThat(url).contains(key).contains("X-Amz-Signature");
  }
}
