package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.dispatchs.BatchWorkerDispatchApplication;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchReceiptPollProperties;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import io.github.pinpols.batch.worker.dispatchs.stage.AckDispatchStep;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = BatchWorkerDispatchApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "batch.worker.dispatch.receipt-poll.enabled=false")
@DisplayName("异步分发回执：本地 HTTP 确认后持久化推进文件状态")
class DispatchReceiptPollIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired
  private FileDispatchRepository dispatchRepository;

  @Autowired
  private PlatformFileRecordRepository fileRecords;

  @Autowired
  private AckDispatchStep ackStep;

  @Test
  @DisplayName("带回执号的异步投递先保持待确认，下游确认后才更新分发和文件状态")
  void shouldKeepPendingUntilReceiverAcknowledges() throws Exception {
    AtomicBoolean acknowledged = new AtomicBoolean(false);
    AtomicInteger receiptRequests = new AtomicInteger();
    AtomicReference<String> receivedQuery = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/receipt", exchange -> {
      receiptRequests.incrementAndGet();
      receivedQuery.set(exchange.getRequestURI().getRawQuery());
      byte[] body = ("{\"acknowledged\":" + acknowledged.get()
              + ",\"receiptCode\":\"receipt-final\"}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (var response = exchange.getResponseBody()) {
        response.write(body);
      }
    });
    server.start();
    DispatchReceiptPollProperties properties = new DispatchReceiptPollProperties();
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(true);
    DispatchReceiptPollScheduler scheduler = new DispatchReceiptPollScheduler(
        properties,
        dispatchRepository,
        new ObjectMapper(),
        fileRecords,
        new SimpleMeterRegistry(),
        security);
    scheduler.initializeMeters();
    try {
      JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
          platformJdbcUrl(), platformJdbcUsername(), platformJdbcPassword()));
      String suffix = UUID.randomUUID().toString();
      String tenantId = "receipt-" + suffix;
      String channelCode = "async-" + suffix;
      String requestId = "request-" + suffix;
      String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/receipt";
      String insertChannelSql = """
          insert into batch.file_channel_config (
            tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
            config_json, receipt_policy, timeout_seconds, enabled, is_deleted
          ) values (?, ?, '异步回执测试', 'API', ?, 'NONE',
            jsonb_build_object('receipt_poll_url', ?), 'POLLING', 30, true, false)
          """;
      jdbc.update(insertChannelSql, tenantId, channelCode, endpoint, endpoint);
      String insertFileSql = """
          insert into batch.file_record (
            tenant_id, file_code, biz_type, file_category, file_name, file_format_type,
            file_size_bytes, checksum_type, storage_type, storage_path, source_type, file_status
          ) values (?, ?, 'OUTPUT', 'OUTPUT', 'receipt.json', 'JSON', 1, 'NONE',
            'LOCAL', ?, 'SYSTEM', 'DISPATCHING') returning id
          """;
      Long fileId = jdbc.queryForObject(insertFileSql, Long.class, tenantId, suffix, "/" + suffix);
      String insertDispatchSql = """
          insert into batch.file_dispatch_record (
            tenant_id, file_id, channel_code, dispatch_status, receipt_code, receipt_status,
            external_request_id, dispatched_at
          ) values (?, ?, ?, 'SENT', 'receipt-initial', 'PENDING', ?, now())
          """;
      jdbc.update(insertDispatchSql, tenantId, fileId, channelCode, requestId);
      DispatchPayload payload = new DispatchPayload(
          fileId.toString(), null, channelCode, null, null, null, null, null, null, null);
      DispatchJobContext context = new DispatchJobContext();
      context.setTenantId(tenantId);
      context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, fileId);
      context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_PAYLOAD, payload);
      context
          .getAttributes()
          .put(
              DispatchRuntimeKeys.DISPATCH_RESULT,
              new DispatchResult(true, requestId, "receipt-initial", false, true, "ok", null));

      DispatchStageResult result = ackStep.execute(context);
      assertThat(result.success()).isTrue();
      String selectDispatchSql = """
          select dispatch_status, receipt_status, receipt_code
          from batch.file_dispatch_record where tenant_id = ? and file_id = ? and channel_code = ?
          """;
      String selectFileStatusSql = "select file_status from batch.file_record where id = ?";
      Map<String, Object> beforePoll =
          jdbc.queryForMap(selectDispatchSql, tenantId, fileId, channelCode);
      assertThat(beforePoll)
          .containsEntry("dispatch_status", "SENT")
          .containsEntry("receipt_status", "PENDING");
      scheduler.poll();
      assertThat(receiptRequests).hasValue(1);
      assertThat(receivedQuery).hasValue("externalRequestId=" + requestId);
      Map<String, Object> beforeConfirmation =
          jdbc.queryForMap(selectDispatchSql, tenantId, fileId, channelCode);
      assertThat(beforeConfirmation)
          .containsEntry("dispatch_status", "SENT")
          .containsEntry("receipt_status", "PENDING");
      String beforeFileStatus = jdbc.queryForObject(selectFileStatusSql, String.class, fileId);
      assertThat(beforeFileStatus).isEqualTo("DISPATCHING");

      acknowledged.set(true);
      scheduler.poll();
      assertThat(receiptRequests).hasValue(2);
      Map<String, Object> afterConfirmation =
          jdbc.queryForMap(selectDispatchSql, tenantId, fileId, channelCode);
      assertThat(afterConfirmation)
          .containsEntry("dispatch_status", "ACKED")
          .containsEntry("receipt_status", "SUCCESS")
          .containsEntry("receipt_code", "receipt-final");
      String afterFileStatus = jdbc.queryForObject(selectFileStatusSql, String.class, fileId);
      assertThat(afterFileStatus).isEqualTo("DISPATCHED");
    } finally {
      scheduler.shutdown();
      server.stop(0);
    }
  }
}
