package io.github.pinpols.batch.worker.imports.stage;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineDefinitionRepository;
import io.github.pinpols.batch.worker.imports.config.WorkerImportPayloadProperties;

/** 仅为纯测试组装默认配置和对象读取协作者，生产代码始终由 Spring 注入。 */
public final class PreprocessStepFixture {

  private PreprocessStepFixture() {}

  public static PreprocessStep create(
      PlatformFileRecordRepository fileRecords,
      PlatformPipelineDefinitionRepository pipelineDefinitions,
      BatchSecurityProperties security,
      BatchObjectCryptoService cryptoService,
      S3StorageProperties storageProperties,
      BatchObjectStore objectStore) {
    WorkerImportPayloadProperties properties = new WorkerImportPayloadProperties();
    ImportPreprocessObjectSource source =
        new ImportPreprocessObjectSource(fileRecords, storageProperties, objectStore, properties);
    return new PreprocessStep(
        fileRecords,
        pipelineDefinitions,
        security,
        cryptoService,
        properties,
        JsonUtils.newDefaultMapper(),
        source);
  }
}
