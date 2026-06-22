package com.example.batch.common.storage;

import com.example.batch.common.config.S3StorageProperties;
import com.example.batch.common.utils.S3BucketSupport;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * 基于 AWS SDK for Java v2 的 S3 实现。覆盖 S3 协议全系（MinIO / AWS S3 / 阿里 OSS / 腾讯 COS / GCS），靠 endpoint +
 * credentials 配置切换后端，非一云一实现。
 */
@Slf4j
@RequiredArgsConstructor
public class S3ObjectStore implements BatchObjectStore {

  private static final String COMPONENT_NAME = "s3-object-store";

  /** S3 DeleteObjects 单批上限(协议规定 ≤1000)。 */
  private static final int MAX_BATCH_DELETE = 1000;

  private final S3Client s3Client;
  private final S3Presigner presigner;
  private final S3StorageProperties properties;

  @Override
  public void put(String bucket, String key, InputStream in, long size, String contentType) {
    ensureBucket(bucket);
    ExactSizeInputStream exact = ExactSizeInputStream.exact(in, "s3", bucket, key, size);
    try {
      if (shouldMultipart(size)) {
        putMultipart(bucket, key, exact, size, contentType);
        return;
      }
      if (size < 0 || size > Integer.MAX_VALUE) {
        throw new ObjectStoreException(
            "s3 object store single put requires known int-sized content length: bucket=%s, key=%s, size=%d"
                .formatted(bucket, key, size));
      }
      byte[] payload = exact.readNBytes((int) size);
      exact.verifyEndOfStream();
      s3Client.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
          RequestBody.fromBytes(payload));
    } catch (ObjectStoreException ex) {
      throw ex;
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

  /** S3 服务端批删({@code DeleteObjects},每批 ≤1000)。不支持该 API 的 S3 兼容后端回退逐个删。 */
  @Override
  public void deleteMany(String bucket, Collection<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return;
    }
    List<String> deduped = keys.stream().filter(k -> k != null && !k.isBlank()).distinct().toList();
    for (int i = 0; i < deduped.size(); i += MAX_BATCH_DELETE) {
      List<String> chunk = deduped.subList(i, Math.min(i + MAX_BATCH_DELETE, deduped.size()));
      try {
        s3Client.deleteObjects(
            DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(
                    Delete.builder()
                        .objects(
                            chunk.stream()
                                .map(k -> ObjectIdentifier.builder().key(k).build())
                                .toList())
                        .quiet(true)
                        .build())
                .build());
      } catch (S3Exception ex) {
        // 部分 S3 兼容后端(个别 SeaweedFS/RustFS 版本)不实现 DeleteObjects(501/NotImplemented):
        // 退化为逐个删,保证可移植。其它错误(权限等)仍按映射抛出。
        String code = ex.awsErrorDetails() == null ? "" : ex.awsErrorDetails().errorCode();
        if (ex.statusCode() == 501 || "NotImplemented".equals(code)) {
          log.warn(
              "s3 backend does not support batch DeleteObjects, falling back to per-key delete:"
                  + " bucket={}, count={}",
              bucket,
              chunk.size());
          chunk.forEach(k -> delete(bucket, k));
        } else {
          throw mapException("deleteMany", bucket, chunk.isEmpty() ? null : chunk.get(0), ex);
        }
      } catch (Exception ex) {
        throw mapException("deleteMany", bucket, chunk.isEmpty() ? null : chunk.get(0), ex);
      }
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
    boolean truncated;
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
        summaries.add(new ObjectSummary(item.key(), item.size(), item.lastModified(), item.eTag()));
      }
      truncated = Boolean.TRUE.equals(resp.isTruncated());
    } catch (Exception ex) {
      throw mapException("list", bucket, prefix, ex);
    }
    // 接口用 startAfter(key) 翻页而非 continuationToken，故以最后一个 key 作 nextMarker；
    // 仅当 S3 标记本页截断（isTruncated）时才给 marker，避免末页恰为整数倍时多一次空查询。
    String nextMarker =
        truncated && !summaries.isEmpty() ? summaries.get(summaries.size() - 1).key() : null;
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

  @Override
  public boolean supportsPresignPut() {
    return true;
  }

  @Override
  public String presignPut(String bucket, String key, Duration ttl, String contentType) {
    try {
      PutObjectRequest.Builder put = PutObjectRequest.builder().bucket(bucket).key(key);
      if (contentType != null && !contentType.isBlank()) {
        put.contentType(contentType);
      }
      PutObjectPresignRequest req =
          PutObjectPresignRequest.builder()
              .signatureDuration(ttl)
              .putObjectRequest(put.build())
              .build();
      return presigner.presignPutObject(req).url().toString();
    } catch (Exception ex) {
      throw mapException("presignPut", bucket, key, ex);
    }
  }

  private void ensureBucket(String bucket) {
    S3BucketSupport.ensureBucket(
        s3Client, bucket, log, COMPONENT_NAME, properties.isAutoCreateBucket());
  }

  private boolean shouldMultipart(long size) {
    return properties.isMultipartEnabled()
        && size >= Math.max(properties.getMultipartThresholdBytes(), minPartSize())
        && properties.getMultipartPartSizeBytes() >= minPartSize();
  }

  private void putMultipart(
      String bucket, String key, ExactSizeInputStream in, long size, String contentType) {
    CreateMultipartUploadResponse created =
        s3Client.createMultipartUpload(
            CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build());
    String uploadId = created.uploadId();
    List<CompletedPart> completedParts = new ArrayList<>();
    long remaining = size;
    int partNumber = 1;
    int partSize = Math.max(properties.getMultipartPartSizeBytes(), minPartSize());
    try {
      while (remaining > 0) {
        int len = (int) Math.min(partSize, remaining);
        byte[] bytes = in.readNBytes(len);
        UploadPartResponse uploaded =
            s3Client.uploadPart(
                UploadPartRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .contentLength((long) bytes.length)
                    .build(),
                RequestBody.fromBytes(bytes));
        completedParts.add(
            CompletedPart.builder().partNumber(partNumber).eTag(uploaded.eTag()).build());
        remaining -= bytes.length;
        partNumber++;
      }
      in.verifyEndOfStream();
      s3Client.completeMultipartUpload(
          CompleteMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(key)
              .uploadId(uploadId)
              .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
              .build());
    } catch (Exception ex) {
      abortMultipart(bucket, key, uploadId);
      if (ex instanceof ObjectStoreException ose) {
        throw ose;
      }
      throw mapException("putMultipart", bucket, key, ex);
    }
  }

  private void abortMultipart(String bucket, String key, String uploadId) {
    try {
      s3Client.abortMultipartUpload(
          AbortMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(uploadId).build());
    } catch (Exception abortEx) {
      log.warn(
          "s3 multipart abort failed: bucket={}, key={}, uploadId={}",
          bucket,
          key,
          uploadId,
          abortEx);
    }
  }

  private static int minPartSize() {
    return 5 * 1024 * 1024;
  }

  private ObjectStoreException mapException(
      String operation, String bucket, String key, Exception ex) {
    if (ex instanceof S3Exception s3) {
      String code = s3.awsErrorDetails() == null ? "" : s3.awsErrorDetails().errorCode();
      String message = message(operation, bucket, key);
      // HEAD 操作（headObject/headBucket）响应无 body，SDK 拿不到 errorCode；只能靠 statusCode 或
      // 类型化的 NoSuchKeyException 识别"对象不存在"。否则 statSize/getFrom 对缺失对象会退化成通用异常。
      if (ex instanceof NoSuchKeyException || s3.statusCode() == 404) {
        return new ObjectNotFoundException(message, ex);
      }
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
