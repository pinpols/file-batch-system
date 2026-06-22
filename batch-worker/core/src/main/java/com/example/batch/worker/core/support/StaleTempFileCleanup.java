package com.example.batch.worker.core.support;

import com.example.batch.common.time.BatchDateTimeSupport;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 系统临时文件清理器：在应用启动就绪时执行一次清理，并定期（默认 PT4H）扫描 JVM 临时目录， 删除超过 {@code
 * batch.worker.stale-temp-file-hours}（默认 6h）未修改且以 {@code batch-} 开头的文件。
 *
 * <p>只清理约定前缀 {@code batch-} 的文件，避免误删系统或其他应用的临时文件。
 */
@Slf4j
@Component
public class StaleTempFileCleanup {

  @Value("${batch.worker.stale-temp-file-hours:6}")
  private long staleTempFileHours;

  @EventListener(ApplicationReadyEvent.class)
  public void cleanOnReady() {
    cleanStaleTempFiles();
  }

  // M-6: 运行期定时清理，防止长时间运行的 worker 累积过多过期临时文件（默认 4 小时一次）
  @Scheduled(fixedDelayString = "${batch.worker.stale-temp-cleanup-interval:PT4H}")
  public void cleanPeriodically() {
    cleanStaleTempFiles();
  }

  void cleanStaleTempFiles() {
    long hours = Math.max(0L, staleTempFileHours);
    Instant cutoff = BatchDateTimeSupport.utcNow().minus(Duration.ofHours(hours));
    Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
    int cleaned = 0;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempDir)) {
      for (Path p : stream) {
        if (p == null) {
          continue;
        }
        try {
          if (!Files.isRegularFile(p)) {
            continue;
          }
          String name = p.getFileName() == null ? "" : p.getFileName().toString();
          if (!name.startsWith("batch-")) {
            continue;
          }
          // 只清理明确约定的 batch-* 前缀，避免误删系统/用户其他临时文件。
          // 若未来 prefix 规则更细（如 batch-import-/batch-export-），建议在这里进一步收紧匹配。
          Instant lastModified = Files.getLastModifiedTime(p).toInstant();
          if (!lastModified.isBefore(cutoff)) {
            continue;
          }
          if (Files.deleteIfExists(p)) {
            cleaned++;
          }
        } catch (RuntimeException ex) {
          log.warn("failed to delete stale temp file: path={}, error={}", p, ex.getMessage());
        } catch (Exception ex) {
          log.warn("failed to delete stale temp file: path={}, error={}", p, ex.getMessage());
        }
      }
    } catch (Exception ex) {
      log.warn(
          "failed to scan temp dir for stale files: tempDir={}, error={}",
          tempDir,
          ex.getMessage());
      return;
    }
    log.info("Cleaned {} stale temp files older than {}h", cleaned, hours);
  }
}
