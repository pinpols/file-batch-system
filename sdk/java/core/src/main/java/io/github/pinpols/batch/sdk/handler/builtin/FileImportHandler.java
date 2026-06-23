package io.github.pinpols.batch.sdk.handler.builtin;

import io.github.pinpols.batch.sdk.handler.SdkAbstractTaskHandler;
import io.github.pinpols.batch.sdk.handler.SdkRowResult;
import io.github.pinpols.batch.sdk.handler.builtin.support.DelimitedCodec;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;

/**
 * 开箱即用的「分隔符文件 → JDBC 表」导入 handler(ADR-036 Import shape 的配置驱动版)。
 *
 * <p>租户零业务代码:给 {@link FileImportConfig} + {@link DataSource} 即可。从 {@code ctx.parameters()}
 * 取文件路径,按格式解析,逐行 batch INSERT 进目标表,显式事务(全部成功才 commit)。
 *
 * <p><b>线程安全</b>:单例可并发执行多任务 —— 所有每任务状态均为方法局部变量,无实例可变字段。
 *
 * <p><b>取值绑定</b>:字段一律以字符串 {@code setObject} 绑定,目标列需文本兼容或由 DB 隐式转换。空字段绑为空串。 列数与文件字段数不一致的行直接
 * fail(报行号)。
 */
@Slf4j
public class FileImportHandler extends SdkAbstractTaskHandler {

  private final FileImportConfig config;
  private final DataSource dataSource;

  public FileImportHandler(FileImportConfig config, DataSource dataSource) {
    this.config = Objects.requireNonNull(config, "config");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    if (config.columns().isEmpty()) {
      throw new IllegalArgumentException("columns must not be empty");
    }
  }

  @Override
  public String taskType() {
    return config.taskType();
  }

  @Override
  protected SdkTaskResult doExecute(SdkTaskContext ctx) {
    Path path = resolveFile(ctx);
    String insertSql = buildInsert();
    char delim = config.format().delimiter();
    char quote = config.format().quote();
    int colCount = config.columns().size();
    SdkRowResult counts = new SdkRowResult();
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
          PreparedStatement ps = conn.prepareStatement(insertSql)) {
        long lineNo = 0;
        int inBatch = 0;
        String line;
        while ((line = reader.readLine()) != null) {
          lineNo++;
          if (lineNo == 1 && config.format().header()) {
            continue;
          }
          if (line.isBlank()) {
            continue;
          }
          List<String> fields = DelimitedCodec.parse(line, delim, quote);
          if (fields.size() != colCount) {
            throw new IllegalArgumentException(
                "line " + lineNo + ": expected " + colCount + " fields, got " + fields.size());
          }
          for (int i = 0; i < colCount; i++) {
            ps.setObject(i + 1, fields.get(i));
          }
          ps.addBatch();
          counts.incSuccess();
          if (++inBatch >= config.batchSize()) {
            ps.executeBatch();
            inBatch = 0;
          }
        }
        if (inBatch > 0) {
          ps.executeBatch();
        }
      }
      conn.commit();
    } catch (Exception ex) {
      log.error("import into {} failed: {}", config.targetTable(), ex.getMessage());
      return SdkTaskResult.fail(ex);
    }
    return SdkTaskResult.ok("imported " + counts.success() + " rows", counts.toOutput());
  }

  private Path resolveFile(SdkTaskContext ctx) {
    Object raw = ctx.parameters().get(config.filePathParam());
    if (!(raw instanceof String p) || p.isBlank()) {
      throw new IllegalArgumentException(
          "missing required parameter '" + config.filePathParam() + "' (file path)");
    }
    Path path = Path.of(p);
    if (!Files.isReadable(path)) {
      throw new IllegalArgumentException("file not readable: " + p);
    }
    return path;
  }

  private String buildInsert() {
    String cols = String.join(", ", config.columns());
    String placeholders = "?,".repeat(config.columns().size());
    placeholders = placeholders.substring(0, placeholders.length() - 1);
    return "INSERT INTO " + config.targetTable() + " (" + cols + ") VALUES (" + placeholders + ")";
  }
}
