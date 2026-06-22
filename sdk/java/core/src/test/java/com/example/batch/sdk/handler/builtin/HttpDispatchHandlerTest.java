package com.example.batch.sdk.handler.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpDispatchHandlerTest {

  private HttpServer server;
  private final AtomicInteger received = new AtomicInteger();
  private volatile int statusToReturn = 200;

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/push",
        exchange -> {
          received.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(statusToReturn, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/push";
  }

  private DataSource twoRowDataSource() throws Exception {
    DataSource ds = mock(DataSource.class);
    Connection conn = mock(Connection.class);
    Statement st = mock(Statement.class);
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData meta = mock(ResultSetMetaData.class);
    when(ds.getConnection()).thenReturn(conn);
    when(conn.createStatement()).thenReturn(st);
    when(st.executeQuery("select id from t")).thenReturn(rs);
    when(rs.getMetaData()).thenReturn(meta);
    when(meta.getColumnCount()).thenReturn(1);
    when(meta.getColumnLabel(1)).thenReturn("id");
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getObject(1)).thenReturn(1, 2);
    return ds;
  }

  private SdkTaskContext ctx() {
    return new SdkTaskContext("t1", "job1", "ti1", 1L, "w1", Map.of(), Map.of());
  }

  @Test
  void shouldPushEachRowAndCountSuccess() throws Exception {
    var config = new HttpDispatchConfig("disp", "select id from t", endpoint(), 5, false, false);
    var handler = new HttpDispatchHandler(config, twoRowDataSource());

    SdkTaskResult result = handler.execute(ctx());

    assertThat(result.success()).isTrue();
    assertThat(received.get()).isEqualTo(2);
    assertThat(result.output()).containsEntry("success", 2L);
  }

  @Test
  void shouldCountFailedButNotStopWhenNotFailFast() throws Exception {
    statusToReturn = 500;
    var config = new HttpDispatchConfig("disp", "select id from t", endpoint(), 5, false, false);
    var handler = new HttpDispatchHandler(config, twoRowDataSource());

    SdkTaskResult result = handler.execute(ctx());

    assertThat(result.success()).isTrue();
    assertThat(received.get()).isEqualTo(2);
    assertThat(result.output()).containsEntry("failed", 2L);
  }

  @Test
  void shouldBlockPrivateIpWhenSsrfGuardOn() throws Exception {
    var config = new HttpDispatchConfig("disp", "select id from t", endpoint(), 5, true, false);
    var handler = new HttpDispatchHandler(config, twoRowDataSource());

    SdkTaskResult result = handler.execute(ctx());

    assertThat(result.success()).isFalse();
    assertThat(received.get()).isZero();
  }
}
