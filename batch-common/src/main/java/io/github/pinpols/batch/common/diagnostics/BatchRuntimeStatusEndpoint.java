package io.github.pinpols.batch.common.diagnostics;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.FilesystemStorageProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.config.StorageBackendGuardProperties;
import io.github.pinpols.batch.common.lifecycle.BatchLifecyclePhases;
import io.github.pinpols.batch.common.utils.Texts;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.core.env.Environment;

/**
 * 只读运行态诊断端点。
 *
 * <p>端点默认不加入 HTTP exposure 列表，只有管理端显式暴露 {@code batchruntime} 后才可访问。响应只包含脱敏后的 effective
 * 配置，供发布验收和排障确认实际选择了哪个后端，不返回任何凭据、完整路径或业务数据。
 */
@Endpoint(id = "batchruntime")
public class BatchRuntimeStatusEndpoint {

  private final Environment environment;
  private final ObjectProvider<BatchSecurityProperties> securityProvider;
  private final ObjectProvider<S3StorageProperties> s3Provider;
  private final ObjectProvider<FilesystemStorageProperties> filesystemProvider;
  private final ObjectProvider<StorageBackendGuardProperties> guardProvider;

  public BatchRuntimeStatusEndpoint(
      Environment environment,
      ObjectProvider<BatchSecurityProperties> securityProvider,
      ObjectProvider<S3StorageProperties> s3Provider,
      ObjectProvider<FilesystemStorageProperties> filesystemProvider,
      ObjectProvider<StorageBackendGuardProperties> guardProvider) {
    this.environment = environment;
    this.securityProvider = securityProvider;
    this.s3Provider = s3Provider;
    this.filesystemProvider = filesystemProvider;
    this.guardProvider = guardProvider;
  }

  @ReadOperation
  public Map<String, Object> status() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("application", environment.getProperty("spring.application.name", "unknown"));
    result.put("profiles", environment.getActiveProfiles());
    result.put("storage", storageStatus());
    result.put("security", securityStatus());
    result.put("lifecycle", lifecycleStatus());
    return result;
  }

  private Map<String, Object> storageStatus() {
    String backend = environment.getProperty("batch.storage.backend", "s3");
    Map<String, Object> storage = new LinkedHashMap<>();
    storage.put("backend", backend);
    StorageBackendGuardProperties guard = guardProvider.getIfAvailable();
    storage.put("backendCutoverConfigured", guard != null && Texts.hasText(guard.getCutoverId()));
    S3StorageProperties s3 = s3Provider.getIfAvailable();
    if ("s3".equalsIgnoreCase(backend) && s3 != null) {
      storage.put("endpointHost", hostOf(s3.getEndpoint()));
      storage.put("bucketConfigured", Texts.hasText(s3.getBucket()));
      storage.put(
          "credentialsConfigured",
          Texts.hasText(s3.getAccessKey()) && Texts.hasText(s3.getSecretKey()));
    }
    FilesystemStorageProperties filesystem = filesystemProvider.getIfAvailable();
    if ("filesystem".equalsIgnoreCase(backend) && filesystem != null) {
      storage.put("rootConfigured", Texts.hasText(filesystem.getRoot()));
      storage.put("downloadBaseUrlConfigured", Texts.hasText(filesystem.getDownloadBaseUrl()));
      storage.put("presignSecretConfigured", Texts.hasText(filesystem.getPresignSecret()));
    }
    return storage;
  }

  private Map<String, Object> securityStatus() {
    Map<String, Object> security = new LinkedHashMap<>();
    BatchSecurityProperties properties = securityProvider.getIfAvailable();
    security.put("bypassMode", properties != null && properties.isBypassMode());
    security.put(
        "internalSecretConfigured",
        properties != null && Texts.hasText(properties.getInternalSecret()));
    return security;
  }

  private static Map<String, Object> lifecycleStatus() {
    Map<String, Object> lifecycle = new LinkedHashMap<>();
    lifecycle.put("firstToStopRelay", BatchLifecyclePhases.FIRST_TO_STOP_RELAY);
    lifecycle.put("workerSdkClient", BatchLifecyclePhases.WORKER_SDK_CLIENT);
    lifecycle.put("managedScheduler", BatchLifecyclePhases.MANAGED_SCHEDULER);
    lifecycle.put("infrastructureDefault", BatchLifecyclePhases.INFRASTRUCTURE_CLIENT_DEFAULT);
    return lifecycle;
  }

  private static String hostOf(String endpoint) {
    if (!Texts.hasText(endpoint)) {
      return "";
    }
    try {
      return URI.create(endpoint).getHost();
    } catch (IllegalArgumentException ex) {
      return "invalid-endpoint";
    }
  }
}
