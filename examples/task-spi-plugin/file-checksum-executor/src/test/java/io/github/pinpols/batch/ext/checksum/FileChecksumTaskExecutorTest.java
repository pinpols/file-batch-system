package io.github.pinpols.batch.ext.checksum;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.spi.task.BatchTaskExecutor;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import java.nio.file.Files;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("文件校验和任务执行器")
class FileChecksumTaskExecutorTest {

  @TempDir java.nio.file.Path tempDir;

  @Test
  @DisplayName("计算 SHA-256 并返回读取字节数")
  void shouldComputeSha256AndReportReadBytes_whenFileExists() throws Exception {
    java.nio.file.Path file = tempDir.resolve("input.txt");
    Files.writeString(file, "abc");
    TaskContext context = context(file.toString());

    var result = new FileChecksumTaskExecutor().execute(context);

    assertThat(result.success()).isTrue();
    assertThat(result.output())
        .containsEntry("sha256", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        .containsEntry("bytesRead", 3L);
  }

  @Test
  @DisplayName("缺少输入文件或输入路径不是常规文件时返回失败")
  void shouldRejectMissingOrNonRegularInput_whenExecuting() throws Exception {
    assertThat(new FileChecksumTaskExecutor().execute(context(" ")).success()).isFalse();

    java.nio.file.Path directory = Files.createDirectory(tempDir.resolve("directory"));
    assertThat(new FileChecksumTaskExecutor().execute(context(directory.toString())).success())
        .isFalse();
  }

  @Test
  @DisplayName("可通过 ServiceLoader 独立发现，且不依赖 Worker Core")
  void shouldDiscoverExecutor_whenUsingServiceLoader() {
    assertThat(ServiceLoader.load(BatchTaskExecutor.class))
        .anyMatch(executor -> executor.taskType().equals(FileChecksumTaskExecutor.TASK_TYPE));
  }

  private static TaskContext context(String inputPath) {
    return new TaskContext(
        "tenant", "checksum-job", "task-1", "external-worker",
        Map.of("inputPath", inputPath), Map.of());
  }
}
