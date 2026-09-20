package io.github.pinpols.batch.worker.exports.infrastructure;

import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.support.AbstractObjectStoreRunCompensator;
import io.github.pinpols.batch.worker.exports.domain.ExportWorkerType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 安全增量补偿(opt-in)EXPORT 实现 —— 反向 = 删本 run 自己上传到对象存储的导出对象 (本 run 的 {@code
 * file_record.storage_path}),幂等、scoped 到本 run。逻辑全在 {@link AbstractObjectStoreRunCompensator}。
 */
@Component
public class ExportObjectStoreCompensator extends AbstractObjectStoreRunCompensator {

  public ExportObjectStoreCompensator(
      PlatformFileRecordRepository fileRecords,
      ObjectProvider<BatchObjectStore> objectStoreProvider) {
    super(fileRecords, objectStoreProvider);
  }

  @Override
  public String pipelineType() {
    return ExportWorkerType.EXPORT;
  }
}
