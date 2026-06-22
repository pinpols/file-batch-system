package com.example.batch.sdk.handler.builtin;

import com.example.batch.sdk.handler.SdkAbstractTaskHandler;
import com.example.batch.sdk.handler.builtin.support.DelimitedCodec;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;

/**
 * 开箱即用的「JDBC 查询 → 分隔符文件」导出 handler(ADR-036 Export shape 的配置驱动版)。
 *
 * <p>租户零业务代码:给 {@link QueryExportConfig} + {@link DataSource} 即可。运行固定查询,游标流式逐行写出到 {@code
 * ctx.parameters()} 指定的文件,按格式编码;可选写列名表头。
 *
 * <p><b>流式</b>:关闭 autoCommit + 设 fetchSize 做服务端游标(对齐 ADR-037,PostgreSQL 默认会一次性拉全量,关 autoCommit
 * 才真流式)。
 *
 * <p><b>线程安全</b>:所有每任务状态均为方法局部变量,无实例可变字段。
 */
@Slf4j
public class QueryExportHandler extends SdkAbstractTaskHandler {

  private final QueryExportConfig config;
  private final DataSource dataSource;

  public QueryExportHandler(QueryExportConfig config, DataSource dataSource) {
    this.config = Objects.requireNonNull(config, "config");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public String taskType() {
    return config.taskType();
  }

  @Override
  protected SdkTaskResult doExecute(SdkTaskContext ctx) {
    Path path = resolveOutput(ctx);
    char delim = config.format().delimiter();
    char quote = config.format().quote();
    long rowCount = 0;
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      try (Statement st = conn.createStatement();
          BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
        st.setFetchSize(config.fetchSize());
        try (ResultSet rs = st.executeQuery(config.query())) {
          ResultSetMetaData meta = rs.getMetaData();
          int cols = meta.getColumnCount();
          if (config.format().header()) {
            List<String> headers = new ArrayList<>(cols);
            for (int i = 1; i <= cols; i++) {
              headers.add(meta.getColumnLabel(i));
            }
            writer.write(DelimitedCodec.encode(headers, delim, quote));
            writer.newLine();
          }
          while (rs.next()) {
            List<String> values = new ArrayList<>(cols);
            for (int i = 1; i <= cols; i++) {
              Object o = rs.getObject(i);
              values.add(o == null ? "" : o.toString());
            }
            writer.write(DelimitedCodec.encode(values, delim, quote));
            writer.newLine();
            rowCount++;
          }
        }
      }
      conn.commit();
    } catch (Exception ex) {
      log.error("export to {} failed: {}", path, ex.getMessage());
      return SdkTaskResult.fail(ex);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("rowCount", rowCount);
    out.put("filePath", path.toString());
    return SdkTaskResult.ok("exported " + rowCount + " rows", out);
  }

  private Path resolveOutput(SdkTaskContext ctx) {
    Object raw = ctx.parameters().get(config.outputPathParam());
    if (!(raw instanceof String p) || p.isBlank()) {
      throw new IllegalArgumentException(
          "missing required parameter '" + config.outputPathParam() + "' (output path)");
    }
    Path path = Path.of(p);
    Path parent = path.getParent();
    if (parent != null && !Files.isDirectory(parent)) {
      throw new IllegalArgumentException("output directory does not exist: " + parent);
    }
    return path;
  }
}
