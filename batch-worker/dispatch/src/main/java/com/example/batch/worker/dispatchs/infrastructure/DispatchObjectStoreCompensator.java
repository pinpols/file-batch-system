package com.example.batch.worker.dispatchs.infrastructure;

import com.example.batch.common.storage.BatchObjectStore;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.AbstractObjectStoreRunCompensator;
import com.example.batch.worker.dispatchs.domain.DispatchWorkerType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 安全增量补偿(opt-in)DISPATCH 实现 —— 反向 = 删本 run 落在对象存储的分发对象 (本 run 的 {@code
 * file_record.storage_path}),幂等、scoped 到本 run。逻辑全在 {@link AbstractObjectStoreRunCompensator}。
 *
 * <p>注:远端投递通道(SFTP/SMTP/NAS 等)的已投递文件不在本反向范围——只删平台对象存储里本 run 自己上传的对象, 不去远端删(远端删除超出"只删本 run
 * 自己、可幂等识别"的安全边界)。
 */
@Component
public class DispatchObjectStoreCompensator extends AbstractObjectStoreRunCompensator {

  public DispatchObjectStoreCompensator(
      PlatformFileRuntimeRepository runtimeRepository,
      ObjectProvider<BatchObjectStore> objectStoreProvider) {
    super(runtimeRepository, objectStoreProvider);
  }

  @Override
  public String pipelineType() {
    return DispatchWorkerType.DISPATCH;
  }
}
