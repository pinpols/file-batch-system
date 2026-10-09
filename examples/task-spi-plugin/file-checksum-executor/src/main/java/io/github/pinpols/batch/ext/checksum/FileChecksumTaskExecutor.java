package io.github.pinpols.batch.ext.checksum;

import io.github.pinpols.batch.common.spi.task.BatchTaskExecutor;
import io.github.pinpols.batch.common.spi.task.ResourceKind;
import io.github.pinpols.batch.common.spi.task.TaskCapability;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** 独立只读任务示例，用于演示第三方 ServiceLoader 执行器。 */
public final class FileChecksumTaskExecutor implements BatchTaskExecutor {

  public static final String TASK_TYPE = "file_sha256";
  private static final String INPUT_PATH = "inputPath";

  @Override
  public String taskType() {
    return TASK_TYPE;
  }

  @Override
  public TaskCapability capability() {
    return new TaskCapability(Set.of(ResourceKind.DISK), true, false, Duration.ofMinutes(5));
  }

  @Override
  public TaskResult execute(TaskContext context) {
    Object value = context.parameters().get(INPUT_PATH);
    if (!(value instanceof String rawPath) || EmptyChecks.isBlank(rawPath)) {
      return TaskResult.fail("parameters.inputPath must be a non-blank path");
    }

    try {
      Path path = Path.of(rawPath).normalize();
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        return TaskResult.fail("inputPath must reference a regular file");
      }
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long bytes = 0;
      try (InputStream input = new DigestInputStream(
          Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS), digest)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
          bytes += read;
        }
      }
      return TaskResult.ok(
          "SHA-256 calculated",
          Map.of("sha256", HexFormat.of().formatHex(digest.digest()), "bytesRead", bytes));
    } catch (Exception exception) {
      return TaskResult.fail(exception);
    }
  }
}
