package com.example.batch.common.storage;

import com.example.batch.common.config.S3StorageProperties;
import com.example.batch.common.utils.S3BucketSupport;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * 基于 AWS SDK for Java v2 的 S3 实现。覆盖 S3 协议全系（MinIO / AWS S3 / 阿里 OSS / 腾讯 COS / GCS），靠 endpoint +
 * credentials 配置切换后端，非一云一实现。
 */
@Slf4j
@RequiredArgsConstructor
public class S3ObjectStore implements BatchObjectStore {

  private static final String COMPONENT_NAME = "s3-object-store";

  private final S3Client s3Client;
  private final S3Presigner presigner;
  private final S3StorageProperties properties;

  @Override
  public void put(String bucket, String key, InputStream in, long size, String contentType) {
    ensureBucket(bucket);
    try {
      s3Client.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
          RequestBody.fromInputStream(in, size));
    } catch (Exception ex) {
      throw mapException("put", bucket, key, ex);
    }
  }

  @Override
  public void copy(String bucket, String srcKey, String dstKey) {
    try {
      s3Client.copyObject(
          CopyObjectRequest.builder()
              .sourceBucket(bucket)
              .sourceKey(srcKey)
              .destinationBucket(bucket)
              .destinationKey(dstKey)
              .build());
    } catch (Exception ex) {
      throw mapException("copy", bucket, srcKey, ex);
    }
  }

  @Override
  public void delete(String bucket, String key) {
    try {
      s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (Exception ex) {
      throw mapException("delete", bucket, key, ex);
    }
  }

  @Override
  public InputStream get(String bucket, String key) {
    try {
      return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (Exception ex) {
      throw mapException("get", bucket, key, ex);
    }
  }

  @Override
  public InputStream getFrom(String bucket, String key, long offset) {
    try {
      return s3Client.getObject(
          GetObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .range("bytes=" + offset + "-")
              .build());
    } catch (Exception ex) {
      throw mapException("getFrom", bucket, key, ex);
    }
  }

  @Override
  public long statSize(String bucket, String key) {
    try {
      return s3Client
          .headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
          .contentLength();
    } catch (Exception ex) {
      throw mapException("statSize", bucket, key, ex);
    }
  }

  @Override
  public boolean exists(String bucket, String key) {
    try {
      s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      return true;
    } catch (NoSuchKeyException ex) {
      return false;
    } catch (S3Exception ex) {
      if (ex.statusCode() == 404) {
        return false;
      }
      throw mapException("exists", bucket, key, ex);
    } catch (Exception ex) {
      throw mapException("exists", bucket, key, ex);
    }
  }

  @Override
  public ObjectListing list(String bucket, String prefix, String afterMarker, int maxKeys) {
    List<ObjectSummary> summaries = new ArrayList<>();
    try {
      ListObjectsV2Response resp =
          s3Client.listObjectsV2(
              ListObjectsV2Request.builder()
                  .bucket(bucket)
                  .prefix(prefix)
                  .startAfter(afterMarker)
                  .maxKeys(maxKeys)
                  .build());
      for (S3Object item : resp.contents()) {
        if (summaries.size() >= maxKeys) {
          break;
        }
        summaries.add(new ObjectSummary(item.key(), item.size(), item.lastModified(), item.eTag()));
      }
    } catch (Exception ex) {
      throw mapException("list", bucket, prefix, ex);
    }
    // 收满 maxKeys → 可能还有下一页，nextMarker 取最后一个 key；否则末页。
    String nextMarker =
        summaries.size() >= maxKeys && !summaries.isEmpty()
            ? summaries.get(summaries.size() - 1).key()
            : null;
    return new ObjectListing(List.copyOf(summaries), nextMarker);
  }

  @Override
  public String presign(String bucket, String key, Duration ttl) {
    try {
      GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
      GetObjectPresignRequest req =
          GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(get).build();
      return presigner.presignGetObject(req).url().toString();
    } catch (Exception ex) {
      throw mapException("presign", bucket, key, ex);
    }
  }

  private void ensureBucket(String bucket) {
    S3BucketSupport.ensureBucket(
        s3Client, bucket, log, COMPONENT_NAME, properties.isAutoCreateBucket());
  }

  private ObjectStoreException mapException(
      String operation, String bucket, String key, Exception ex) {
    if (ex instanceof S3Exception s3) {
      String code = s3.awsErrorDetails() == null ? "" : s3.awsErrorDetails().errorCode();
      String message = message(operation, bucket, key);
      return switch (code == null ? "" : code) {
        case "NoSuchKey", "NoSuchObject" -> new ObjectNotFoundException(message, ex);
        case "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch" ->
            new ObjectStoreAccessException(message, ex);
        default -> new ObjectStoreException(message, ex);
      };
    }
    return new ObjectStoreException(message(operation, bucket, key), ex);
  }

  private static String message(String operation, String bucket, String key) {
    return "s3 object store %s failed: bucket=%s, key=%s".formatted(operation, bucket, key);
  }
}
