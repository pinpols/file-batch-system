package io.github.pinpols.batch.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Opt-in live S3 contract check against a locally managed S3-compatible service. */
@EnabledIfEnvironmentVariable(named = "S3_COMPAT_POC_ENDPOINT", matches = "https?://.+")
class S3CompatibleObjectStorePocTest {

  private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "");
  private static final String BUCKET = "s3-compat-poc-" + RUN_ID.substring(0, 16);
  private static final String PREFIX = "poc/" + RUN_ID + "/";
  private static final List<String> CREATED_KEYS = new ArrayList<>();

  private static S3Client s3Client;
  private static S3Presigner presigner;
  private static S3ObjectStore store;
  private static String endpoint;

  @BeforeAll
  static void createStore() {
    endpoint = requiredEnv("S3_COMPAT_POC_ENDPOINT");
    String accessKey = requiredEnv("S3_COMPAT_POC_ACCESS_KEY");
    String secretKey = requiredEnv("S3_COMPAT_POC_SECRET_KEY");
    boolean pathStyleEnabled = booleanEnv("S3_COMPAT_POC_PATH_STYLE_ENABLED", true);
    String region = env("S3_COMPAT_POC_REGION", "us-east-1");
    RequestChecksumCalculation requestChecksumCalculation = RequestChecksumCalculation.valueOf(
        env("S3_COMPAT_POC_REQUEST_CHECKSUM_CALCULATION", "WHEN_REQUIRED"));
    ResponseChecksumValidation responseChecksumValidation = ResponseChecksumValidation.valueOf(
        env("S3_COMPAT_POC_RESPONSE_CHECKSUM_VALIDATION", "WHEN_REQUIRED"));
    StaticCredentialsProvider credentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    URI endpointUri = URI.create(endpoint);

    s3Client = S3Client.builder()
        .endpointOverride(endpointUri)
        .credentialsProvider(credentials)
        .forcePathStyle(pathStyleEnabled)
        .requestChecksumCalculation(requestChecksumCalculation)
        .responseChecksumValidation(responseChecksumValidation)
        .region(Region.of(region))
        .build();
    presigner = S3Presigner.builder()
        .endpointOverride(endpointUri)
        .credentialsProvider(credentials)
        .serviceConfiguration(
            S3Configuration.builder().pathStyleAccessEnabled(pathStyleEnabled).build())
        .region(Region.of(region))
        .build();

    S3StorageProperties properties = new S3StorageProperties();
    properties.setEndpoint(endpoint);
    properties.setAccessKey(accessKey);
    properties.setSecretKey(secretKey);
    properties.setBucket(BUCKET);
    properties.setAutoCreateBucket(true);
    properties.setRegion(region);
    properties.setPathStyleEnabled(pathStyleEnabled);
    properties.setRequestChecksumCalculation(requestChecksumCalculation);
    properties.setResponseChecksumValidation(responseChecksumValidation);
    properties.setMultipartThresholdBytes(8L * 1024 * 1024);
    properties.setMultipartPartSizeBytes(5 * 1024 * 1024);
    store = new S3ObjectStore(s3Client, presigner, properties);
  }

  @Test
  void shouldRejectInvalidCredentials() {
    try (S3Client client = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create("invalid-access-key", "invalid-secret-key")))
        .forcePathStyle(booleanEnv("S3_COMPAT_POC_PATH_STYLE_ENABLED", true))
        .region(Region.of(env("S3_COMPAT_POC_REGION", "us-east-1")))
        .build()) {
      assertThatThrownBy(client::listBuckets)
          .isInstanceOf(S3Exception.class)
          .satisfies(error -> assertThat(((S3Exception) error).statusCode()).isEqualTo(403));
    }
  }

  @AfterAll
  static void closeStore() {
    if (store != null) {
      store.deleteMany(BUCKET, CREATED_KEYS);
    }
    if (presigner != null) {
      presigner.close();
    }
    if (s3Client != null) {
      s3Client.close();
    }
  }

  @Test
  void shouldExerciseApplicationS3Contract() throws Exception {
    String objectKey = PREFIX + "roundtrip.txt";
    byte[] content = "S3-compatible object store contract".getBytes(StandardCharsets.UTF_8);
    put(objectKey, content, "text/plain");

    assertThat(store.exists(BUCKET, objectKey)).isTrue();
    assertThat(store.statSize(BUCKET, objectKey)).isEqualTo(content.length);
    try (InputStream input = store.get(BUCKET, objectKey)) {
      assertThat(input.readAllBytes()).isEqualTo(content);
    }
    int rangeOffset = 6;
    try (InputStream input = store.getFrom(BUCKET, objectKey, rangeOffset)) {
      assertThat(input.readAllBytes())
          .isEqualTo(Arrays.copyOfRange(content, rangeOffset, content.length));
    }

    String copiedKey = PREFIX + "copy.txt";
    store.copy(BUCKET, objectKey, copiedKey);
    CREATED_KEYS.add(copiedKey);
    try (InputStream input = store.get(BUCKET, copiedKey)) {
      assertThat(input.readAllBytes()).isEqualTo(content);
    }

    String pagePrefix = PREFIX + "list/";
    for (int index = 0; index < 3; index++) {
      put(pagePrefix + "item-" + index, new byte[] {(byte) index}, "application/octet-stream");
    }
    ObjectListing firstPage = store.list(BUCKET, pagePrefix, null, 2);
    assertThat(firstPage.objects()).hasSize(2);
    ObjectListing secondPage = store.list(BUCKET, pagePrefix, firstPage.nextMarker(), 2);
    assertThat(secondPage.objects()).hasSize(1);

    String presignedPutKey = PREFIX + "presigned-put.txt";
    String putUrl = store.presignPut(BUCKET, presignedPutKey, Duration.ofMinutes(2), "text/plain");
    HttpResponse<String> putResponse = HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(putUrl))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("presigned upload"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(putResponse.statusCode()).isBetween(200, 299);
    CREATED_KEYS.add(presignedPutKey);

    String getUrl = store.presign(BUCKET, presignedPutKey, Duration.ofMinutes(2));
    HttpResponse<String> getResponse = HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(getUrl)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(getResponse.statusCode()).isEqualTo(200);
    assertThat(getResponse.body()).isEqualTo("presigned upload");

    String multipartKey = PREFIX + "multipart.bin";
    byte[] multipartBody = new byte[11 * 1024 * 1024 + 17];
    for (int index = 0; index < multipartBody.length; index++) {
      multipartBody[index] = (byte) (index % 251);
    }
    put(multipartKey, multipartBody, "application/octet-stream");
    assertThat(store.statSize(BUCKET, multipartKey)).isEqualTo(multipartBody.length);
    try (InputStream input = store.get(BUCKET, multipartKey)) {
      assertThat(input.readAllBytes()).isEqualTo(multipartBody);
    }

    List<String> deleteKeys = List.of(pagePrefix + "item-0", pagePrefix + "item-1");
    store.deleteMany(BUCKET, deleteKeys);
    for (String key : deleteKeys) {
      CREATED_KEYS.remove(key);
      assertThat(store.exists(BUCKET, key)).isFalse();
    }
  }

  @Test
  void shouldRunComparableThirtySecondMixedLoad(TestReporter testReporter) throws Exception {
    int workers = intEnv("S3_COMPAT_POC_LOAD_WORKERS", 4);
    int payloadSize = intEnv("S3_COMPAT_POC_LOAD_PAYLOAD_BYTES", 1024 * 1024);
    int durationSeconds = intEnv("S3_COMPAT_POC_LOAD_SECONDS", 30);
    byte[] payload = new byte[payloadSize];
    Arrays.fill(payload, (byte) 0x5a);
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch start = new CountDownLatch(1);
    AtomicLong puts = new AtomicLong();
    AtomicLong gets = new AtomicLong();
    AtomicLong endNanos = new AtomicLong();
    List<Future<?>> futures = new ArrayList<>();
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    long[] startNanos = new long[1];

    for (int worker = 0; worker < workers; worker++) {
      String key = PREFIX + "load/worker-" + worker + ".bin";
      CREATED_KEYS.add(key);
      futures.add(executor.submit(() -> {
        ready.countDown();
        start.await();
        while (System.nanoTime() < endNanos.get()) {
          store.put(
              BUCKET,
              key,
              new ByteArrayInputStream(payload),
              payload.length,
              "application/octet-stream");
          puts.incrementAndGet();
          try (InputStream input = store.get(BUCKET, key)) {
            assertThat(input.readAllBytes()).isEqualTo(payload);
          }
          gets.incrementAndGet();
        }
        return null;
      }));
    }

    try {
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      startNanos[0] = System.nanoTime();
      endNanos.set(startNanos[0] + TimeUnit.SECONDS.toNanos(durationSeconds));
      start.countDown();
      for (Future<?> future : futures) {
        future.get();
      }
      long elapsedNanos = System.nanoTime() - startNanos[0];
      testReporter.publishEntry(
          "s3.mixed-load",
          "workers=" + workers
              + ", payloadBytes=" + payloadSize
              + ", durationSeconds=" + durationSeconds
              + ", puts=" + puts.get()
              + ", gets=" + gets.get()
              + ", elapsedSeconds=" + String.format("%.2f", elapsedNanos / 1_000_000_000.0)
              + ", operationsPerSecond="
              + String.format(
                  "%.2f", (puts.get() + gets.get()) / (elapsedNanos / 1_000_000_000.0)));
      assertThat(puts.get()).isPositive();
      assertThat(gets.get()).isEqualTo(puts.get());
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static void put(String key, byte[] content, String contentType) {
    store.put(BUCKET, key, new ByteArrayInputStream(content), content.length, contentType);
    CREATED_KEYS.add(key);
  }

  private static String requiredEnv(String name) {
    String value = System.getenv(name);
    if (EmptyChecks.isBlank(value)) {
      throw new IllegalStateException("required environment variable is not set: " + name);
    }
    return value;
  }

  private static String env(String name, String defaultValue) {
    String value = System.getenv(name);
    return EmptyChecks.isBlank(value) ? defaultValue : value.trim();
  }

  private static boolean booleanEnv(String name, boolean defaultValue) {
    String value = System.getenv(name);
    return EmptyChecks.isBlank(value) ? defaultValue : Boolean.parseBoolean(value);
  }

  private static int intEnv(String name, int defaultValue) {
    String value = env(name, Integer.toString(defaultValue));
    return Integer.parseInt(value);
  }
}
