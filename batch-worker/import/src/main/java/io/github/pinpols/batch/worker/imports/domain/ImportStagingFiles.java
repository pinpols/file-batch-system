package io.github.pinpols.batch.worker.imports.domain;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.PrivateTempFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 单次导入的阶段文件作用域;运行中持锁,成功收尾删除,失败收尾解锁并保留取证文件。 */
public final class ImportStagingFiles implements AutoCloseable {

  private final List<PrivateTempFiles.LockedTempFile> files = new ArrayList<>();
  private boolean closed;
  private boolean successful;

  /** 文件跨 PARSE/VALIDATE/LOAD 使用,锁句柄由整个导入作用域持有。 */
  public Path createTempFile(String prefix, String suffix) throws IOException {
    if (closed) {
      throw new IllegalStateException("import staging file scope is already closed");
    }
    PrivateTempFiles.LockedTempFile file = PrivateTempFiles.createLockedTempFile(prefix, suffix);
    files.add(file);
    return file.path();
  }

  /** 所有阶段成功后标记统一回收,覆盖 dry-run 和提前返回未经过 LOAD 删除的文件。 */
  public void markSuccessful() {
    successful = true;
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    IOException failure = null;
    for (PrivateTempFiles.LockedTempFile file : files) {
      try {
        if (successful) {
          file.close();
        } else {
          file.releaseLock();
        }
      } catch (IOException exception) {
        if (EmptyChecks.isNull(failure)) {
          failure = exception;
        } else {
          failure.addSuppressed(exception);
        }
      }
    }
    files.clear();
    if (EmptyChecks.isNotNull(failure)) {
      throw failure;
    }
  }
}
