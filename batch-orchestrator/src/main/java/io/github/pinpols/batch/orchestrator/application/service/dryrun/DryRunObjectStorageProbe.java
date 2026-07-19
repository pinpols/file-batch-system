package io.github.pinpols.batch.orchestrator.application.service.dryrun;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.utils.Texts;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/** L3 对象存储探测，负责 bucket 参数解析、命名校验和存在性检查。 */
final class DryRunObjectStorageProbe {

  private static final String SCOPE_EXECUTION = "execution";
  private static final Pattern S3_BUCKET_PATTERN =
      Pattern.compile("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$");

  private final ObjectProvider<S3Client> s3ClientProvider;
  private final ObjectProvider<S3StorageProperties> s3PropertiesProvider;

  DryRunObjectStorageProbe(
      ObjectProvider<S3Client> s3ClientProvider,
      ObjectProvider<S3StorageProperties> s3PropertiesProvider) {
    this.s3ClientProvider = s3ClientProvider;
    this.s3PropertiesProvider = s3PropertiesProvider;
  }

  int probe(Map<String, Object> params, List<DryRunFinding> findings) {
    String bucket = stringValue(params, "s3Bucket");
    if (!Texts.hasText(bucket)) {
      S3StorageProperties properties = s3PropertiesProvider.getIfAvailable();
      bucket = properties == null ? null : properties.getBucket();
    }
    if (!Texts.hasText(bucket)) {
      return 0;
    }
    if (!S3_BUCKET_PATTERN.matcher(bucket).matches()) {
      findings.add(
          DryRunFinding.error(
              "EXEC_S3_BUCKET_INVALID",
              SCOPE_EXECUTION,
              "s3 bucket name does not match DNS-style rule: " + bucket,
              bucket));
      return 1;
    }
    S3Client client = s3ClientProvider.getIfAvailable();
    if (client == null) {
      findings.add(
          DryRunFinding.warn(
              "EXEC_S3_CLIENT_UNAVAILABLE",
              SCOPE_EXECUTION,
              "S3Client bean unavailable; bucket name passed regex only",
              bucket));
      return 1;
    }
    probeBucket(client, bucket, findings);
    return 1;
  }

  private void probeBucket(S3Client client, String bucket, List<DryRunFinding> findings) {
    try {
      boolean exists;
      try {
        client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        exists = true;
      } catch (NoSuchBucketException notFound) {
        exists = false;
      }
      if (exists) {
        findings.add(
            DryRunFinding.pass(
                "EXEC_S3_BUCKET_OK", SCOPE_EXECUTION, "s3 bucket exists: " + bucket));
      } else {
        findings.add(
            DryRunFinding.error(
                "EXEC_S3_BUCKET_MISSING",
                SCOPE_EXECUTION,
                "s3 bucket not found: " + bucket,
                bucket));
      }
    } catch (Exception ex) {
      findings.add(
          DryRunFinding.warn(
              "EXEC_S3_PROBE_FAILED",
              SCOPE_EXECUTION,
              "s3 probe failed: " + ex.getMessage(),
              bucket));
    }
  }

  private static String stringValue(Map<String, Object> params, String key) {
    Object raw = params == null ? null : params.get(key);
    return raw instanceof String string ? string : null;
  }
}
