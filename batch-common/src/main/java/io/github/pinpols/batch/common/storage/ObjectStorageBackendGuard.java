package io.github.pinpols.batch.common.storage;

import io.github.pinpols.batch.common.config.FilesystemStorageProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.config.StorageBackendGuardProperties;
import io.github.pinpols.batch.common.stateful.StatefulBackendGuard;
import io.github.pinpols.batch.common.stateful.StatefulBackendIdentity;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/** Refuses unmarked S3/filesystem backend and location changes before the service becomes ready. */
@Slf4j
public class ObjectStorageBackendGuard implements ApplicationRunner, Ordered {

  static final String FEATURE_KEY = "object-storage";

  private final StatefulBackendGuard guard;
  private final S3StorageProperties s3;
  private final FilesystemStorageProperties filesystem;
  private final StorageBackendGuardProperties properties;
  private final Environment environment;

  public ObjectStorageBackendGuard(
      DataSource platformDataSource,
      S3StorageProperties s3,
      FilesystemStorageProperties filesystem,
      StorageBackendGuardProperties properties,
      Environment environment) {
    this.guard = new StatefulBackendGuard(platformDataSource);
    this.s3 = s3;
    this.filesystem = filesystem;
    this.properties = properties;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    StatefulBackendGuard.DesiredBackend desired = desiredBackend();
    StatefulBackendGuard.GuardResult result = guard.verify(desired);
    log.info(
        "object storage backend guard {}: backend={}, identity={}, generation={}",
        result.action(),
        desired.backend(),
        desired.backendIdentity(),
        result.generation());
  }

  StatefulBackendGuard.DesiredBackend desiredBackend() {
    String backend = environment.getProperty("batch.storage.backend", "s3").trim().toLowerCase();
    String identity =
        switch (backend) {
          case "s3" -> StatefulBackendIdentity.s3(s3.getEndpoint(), s3.getRegion(), s3.getBucket());
          case "filesystem" ->
              StatefulBackendIdentity.filesystem(filesystem.getRoot(), s3.getBucket());
          default ->
              throw new IllegalStateException("unsupported batch.storage.backend: " + backend);
        };
    String actor = environment.getProperty("spring.application.name", "batch-service");
    return new StatefulBackendGuard.DesiredBackend(
        FEATURE_KEY, backend, identity, properties.getCutoverId(), actor);
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 20;
  }
}
