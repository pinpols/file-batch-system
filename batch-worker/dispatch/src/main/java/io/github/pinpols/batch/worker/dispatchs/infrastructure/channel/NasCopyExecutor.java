package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchRuntimeProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Spring 生命周期管理的 NAS 阻塞复制执行器。 */
@Slf4j
@Component
final class NasCopyExecutor {

  static final int MAX_THREADS = 8;
  private static final AtomicLong THREAD_INDEX = new AtomicLong();

  private final AtomicBoolean stopping = new AtomicBoolean();
  private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
      0,
      MAX_THREADS,
      60L,
      TimeUnit.SECONDS,
      new SynchronousQueue<>(),
      runnable -> {
        Thread thread = new Thread(runnable, "nas-copy-" + THREAD_INDEX.incrementAndGet());
        thread.setDaemon(true);
        return thread;
      },
      new ThreadPoolExecutor.AbortPolicy());

  void copy(InputStream input, Path target, DispatchRuntimeProperties properties)
      throws IOException {
    if (stopping.get()) {
      throw new IOException("NAS copy executor is stopping");
    }
    Future<?> future;
    try {
      future = executor.submit(() -> {
        try {
          Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
          throw new UncheckedIOException(exception);
        }
      });
    } catch (RejectedExecutionException exception) {
      log.warn("NAS copy thread pool exhausted (max={}), rejecting dispatch fast", MAX_THREADS);
      throw new IOException(
          "NAS copy thread pool exhausted (max=" + MAX_THREADS + "); retry later", exception);
    }
    awaitCopy(future, properties);
  }

  private static void awaitCopy(Future<?> future, DispatchRuntimeProperties properties)
      throws IOException {
    try {
      future.get(properties.getNasCopyTimeoutSeconds(), TimeUnit.SECONDS);
    } catch (TimeoutException exception) {
      future.cancel(true);
      throw new IOException(
          "NAS Files.copy timed out after "
              + properties.getNasCopyTimeoutSeconds()
              + "s - likely stale NFS mount or hung remote",
          exception);
    } catch (InterruptedException exception) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new IOException("NAS Files.copy interrupted", exception);
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof Error error) {
        throw error;
      }
      if (cause instanceof IOException ioException) {
        throw ioException;
      }
      if (cause instanceof UncheckedIOException uncheckedIOException) {
        throw uncheckedIOException.getCause();
      }
      throw new IOException("NAS Files.copy failed", EmptyChecks.isNull(cause) ? exception : cause);
    }
  }

  @PreDestroy
  void shutdown() {
    if (!stopping.compareAndSet(false, true)) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("NAS copy executor did not drain within 5s; forcing interruption");
        executor.shutdownNow();
      }
    } catch (InterruptedException exception) {
      SwallowedExceptionLogger.info(NasCopyExecutor.class, "catch:InterruptedException", exception);
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
