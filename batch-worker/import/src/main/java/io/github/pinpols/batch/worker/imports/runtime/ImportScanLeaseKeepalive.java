package io.github.pinpols.batch.worker.imports.runtime;

import io.github.pinpols.batch.worker.imports.runtime.ImportIngressScanRepository.Lease;
import java.util.function.LongSupplier;

/** 大文件摘要计算期间按时间续租，避免将对象流读取放入数据库事务。 */
final class ImportScanLeaseKeepalive {
  private static final long RENEW_INTERVAL_NANOS = 20_000_000_000L;

  private final ImportIngressScanRepository repository;
  private final Lease lease;
  private final LongSupplier clock;
  private long lastRenewal;

  ImportScanLeaseKeepalive(ImportIngressScanRepository repository, Lease lease) {
    this(repository, lease, System::nanoTime);
  }

  ImportScanLeaseKeepalive(
      ImportIngressScanRepository repository, Lease lease, LongSupplier clock) {
    this.repository = repository;
    this.lease = lease;
    this.clock = clock;
    this.lastRenewal = clock.getAsLong();
  }

  void renewIfDue() {
    long now = clock.getAsLong();
    if (now - lastRenewal < RENEW_INTERVAL_NANOS) {
      return;
    }
    if (!repository.fenced(lease, () -> {})) {
      throw new IllegalStateException("扫描租约已失效，停止校验对象");
    }
    lastRenewal = now;
  }
}
