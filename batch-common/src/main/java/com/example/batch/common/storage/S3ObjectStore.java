package com.example.batch.common.storage;

import com.example.batch.common.config.S3StorageProperties;
import com.example.batch.common.utils.MinioBucketSupport;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Item;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于 MinIO Java SDK 的 S3 协议对象存储实现。覆盖 S3 协议全系（MinIO / AWS S3 / 阿里 OSS / 腾讯 COS / GCS），靠 endpoint +
 * credentials 配置切换后端，非一云一实现。
 *
 * <p>这是 §11 命名边界里「SDK 包装内层」——它内部包的就是 {@code io.minio} 库，是全仓唯一允许直 {@code import io.minio} 的收口点。
 */
@Slf4j
@RequiredArgsConstructor
public class S3ObjectStore implements BatchObjectStore {

  private static final String COMPONENT_NAME = "s3-object-store";

  private final MinioClient minioClient;
  private final S3StorageProperties properties;

  @Override
  public void put(String bucket, String key, InputStream in, long size, String contentType) {
    ensureBucket(bucket);
    try {
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(key).stream(in, size, -1)
              .contentType(contentType)
              .build());
    } catch (Exception ex) {
      throw mapException("put", bucket, key, ex);
    }
  }

  @Override
  public void copy(String bucket, String srcKey, String dstKey) {
    try {
      minioClient.copyObject(
          CopyObjectArgs.builder()
              .bucket(bucket)
              .object(dstKey)
              .source(CopySource.builder().bucket(bucket).object(srcKey).build())
              .build());
    } catch (Exception ex) {
      throw mapException("copy", bucket, srcKey, ex);
    }
  }

  @Override
  public void delete(String bucket, String key) {
    try {
      minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception ex) {
      throw mapException("delete", bucket, key, ex);
    }
  }

  @Override
  public InputStream get(String bucket, String key) {
    try {
      return minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception ex) {
      throw mapException("get", bucket, key, ex);
    }
  }

  @Override
  public InputStream getFrom(String bucket, String key, long offset) {
    try {
      return minioClient.getObject(
          GetObjectArgs.builder().bucket(bucket).object(key).offset(offset).build());
    } catch (Exception ex) {
      throw mapException("getFrom", bucket, key, ex);
    }
  }

  @Override
  public long statSize(String bucket, String key) {
    try {
      return minioClient
          .statObject(StatObjectArgs.builder().bucket(bucket).object(key).build())
          .size();
    } catch (Exception ex) {
      throw mapException("statSize", bucket, key, ex);
    }
  }

  @Override
  public boolean exists(String bucket, String key) {
    try {
      minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
      return true;
    } catch (ErrorResponseException ex) {
      String code = ex.errorResponse().code();
      if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
        return false;
      }
      throw mapErrorResponse("exists", bucket, key, ex, code);
    } catch (Exception ex) {
      throw mapException("exists", bucket, key, ex);
    }
  }

  @Override
  public ObjectListing list(String bucket, String prefix, String afterMarker, int maxKeys) {
    List<ObjectSummary> summaries = new ArrayList<>();
    try {
      Iterable<Result<Item>> results =
          minioClient.listObjects(
              ListObjectsArgs.builder()
                  .bucket(bucket)
                  .prefix(prefix)
                  .startAfter(afterMarker)
                  .maxKeys(maxKeys)
                  .build());
      for (Result<Item> result : results) {
        if (summaries.size() >= maxKeys) {
          // MinIO SDK 的 maxKeys 只控制每次请求页大小、不限制迭代器总量（内部自动翻 continuation
          // token）。这里手动在收满 maxKeys 时停止，对齐接口的「单页最多 maxKeys」语义。
          break;
        }
        Item item = result.get();
        summaries.add(
            new ObjectSummary(
                item.objectName(),
                item.size(),
                item.lastModified() == null ? null : item.lastModified().toInstant(),
                item.etag()));
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
      return minioClient.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .method(Method.GET)
              .bucket(bucket)
              .object(key)
              .expiry((int) ttl.toSeconds())
              .build());
    } catch (Exception ex) {
      throw mapException("presign", bucket, key, ex);
    }
  }

  private void ensureBucket(String bucket) {
    MinioBucketSupport.ensureBucket(
        minioClient, bucket, log, COMPONENT_NAME, properties.isAutoCreateBucket());
  }

  private ObjectStoreException mapException(
      String operation, String bucket, String key, Exception ex) {
    if (ex instanceof ErrorResponseException errorResponse) {
      return mapErrorResponse(
          operation, bucket, key, errorResponse, errorResponse.errorResponse().code());
    }
    return new ObjectStoreException(message(operation, bucket, key), ex);
  }

  private ObjectStoreException mapErrorResponse(
      String operation, String bucket, String key, ErrorResponseException ex, String code) {
    String message = message(operation, bucket, key);
    return switch (code == null ? "" : code) {
      case "NoSuchKey", "NoSuchObject" -> new ObjectNotFoundException(message, ex);
      case "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch" ->
          new ObjectStoreAccessException(message, ex);
      default -> new ObjectStoreException(message, ex);
    };
  }

  private static String message(String operation, String bucket, String key) {
    return "s3 object store %s failed: bucket=%s, key=%s".formatted(operation, bucket, key);
  }
}
