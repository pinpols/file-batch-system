package io.github.pinpols.batch.sdk.handler.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.sdk.handler.SdkAbstractTaskHandler;
import io.github.pinpols.batch.sdk.handler.SdkRowResult;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;

/**
 * 开箱即用的「JDBC 查询 → HTTP 逐行推送」分发 handler(ADR-036 Dispatch shape 的配置驱动版)。
 *
 * <p>租户零业务代码:给 {@link HttpDispatchConfig} + {@link DataSource} 即可。运行查询,每行序列化成 JSON 作为 body POST 到
 * {@code endpoint};2xx 计 success,其余计 failed。默认单条失败不中断整批({@link
 * HttpDispatchConfig#failFast()}=false)。
 *
 * <p><b>SSRF</b>:endpoint 在执行前解析 host 校验一次(拦私网 / 环回);用 JDK {@link HttpClient},无第三方 HTTP 库。
 *
 * <p><b>线程安全</b>:{@link ObjectMapper} / {@link HttpClient} 均线程安全,其余每任务状态为方法局部变量,无可变实例字段。
 */
@Slf4j
public class HttpDispatchHandler extends SdkAbstractTaskHandler {

  private final HttpDispatchConfig config;
  private final DataSource dataSource;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public HttpDispatchHandler(HttpDispatchConfig config, DataSource dataSource) {
    this(config, dataSource, new ObjectMapper());
  }

  public HttpDispatchHandler(
      HttpDispatchConfig config, DataSource dataSource, ObjectMapper objectMapper) {
    this.config = Objects.requireNonNull(config, "config");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.timeoutSeconds())).build();
  }

  @Override
  public String taskType() {
    return config.taskType();
  }

  @Override
  protected SdkTaskResult doExecute(SdkTaskContext ctx) {
    URI uri = URI.create(config.endpoint());
    try {
      checkSsrf(uri);
    } catch (Exception ex) {
      return SdkTaskResult.fail(ex);
    }
    SdkRowResult counts = new SdkRowResult();
    try (Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(config.selectQuery())) {
      ResultSetMetaData meta = rs.getMetaData();
      int cols = meta.getColumnCount();
      while (rs.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= cols; i++) {
          row.put(meta.getColumnLabel(i), rs.getObject(i));
        }
        pushRow(ctx, uri, row, counts);
      }
    } catch (Exception ex) {
      log.error("dispatch from query failed: {}", ex.getMessage());
      return SdkTaskResult.fail(ex);
    }
    return SdkTaskResult.ok(
        "dispatched " + counts.success() + " ok, " + counts.failed() + " failed",
        counts.toOutput());
  }

  private void pushRow(SdkTaskContext ctx, URI uri, Map<String, Object> row, SdkRowResult counts)
      throws Exception {
    if (ctx.isCancelled()) {
      throw new IllegalStateException("cancelled by platform");
    }
    String json = objectMapper.writeValueAsString(row);
    try {
      HttpRequest req =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(config.timeoutSeconds()))
              .header("Content-Type", "application/json")
              .POST(BodyPublishers.ofString(json, StandardCharsets.UTF_8))
              .build();
      HttpResponse<Void> resp = httpClient.send(req, BodyHandlers.discarding());
      if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
        counts.incSuccess();
      } else {
        counts.incFailed();
        if (config.failFast()) {
          throw new IllegalStateException("push failed with status " + resp.statusCode());
        }
      }
    } catch (Exception ex) {
      counts.incFailed();
      if (config.failFast()) {
        throw ex;
      }
      log.warn("push row failed (continuing): {}", ex.getMessage());
    }
  }

  private void checkSsrf(URI uri) throws Exception {
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("invalid endpoint, no host: " + config.endpoint());
    }
    if (config.blockPrivateIps()) {
      InetAddress addr = InetAddress.getByName(host);
      if (addr.isLoopbackAddress()
          || addr.isAnyLocalAddress()
          || addr.isLinkLocalAddress()
          || addr.isSiteLocalAddress()) {
        throw new SecurityException("SSRF blocked: private/loopback IP for host " + host);
      }
    }
  }
}
