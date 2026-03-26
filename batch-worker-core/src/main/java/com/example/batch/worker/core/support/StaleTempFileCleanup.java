package com.example.batch.worker.core.support;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StaleTempFileCleanup {

    @Value("${batch.worker.stale-temp-file-hours:6}")
    private long staleTempFileHours;

    @EventListener(ApplicationReadyEvent.class)
    public void cleanOnReady() {
        cleanStaleTempFiles();
    }

    void cleanStaleTempFiles() {
        long hours = Math.max(0L, staleTempFileHours);
        Instant cutoff = Instant.now().minus(Duration.ofHours(hours));
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
            log.warn("failed to scan temp dir for stale files: tempDir={}, error={}", tempDir, ex.getMessage());
            return;
        }
        log.info("Cleaned {} stale temp files older than {}h", cleaned, hours);
    }
}

