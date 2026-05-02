package com.example.batch.console.infrastructure.report;

import static com.example.batch.console.support.excel.ConsoleExcelStyles.createHeaderStyle;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setReadmeColumnWidth;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeCell;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeHeaders;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.application.config.ConsoleConfigApplicationService;
import com.example.batch.console.application.report.ConsoleQueryApplicationService;
import com.example.batch.console.application.report.ConsoleReportExcelApplicationService;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.support.excel.ConsoleExcelStyles;
import com.example.batch.console.web.query.AuditLogQueryRequest;
import com.example.batch.console.web.query.ConfigChangeLogQueryRequest;
import com.example.batch.console.web.query.ConfigReleaseQueryRequest;
import com.example.batch.console.web.query.OutboxDeliveryLogQueryRequest;
import com.example.batch.console.web.query.OutboxRetryLogQueryRequest;
import com.example.batch.console.web.query.SecretVersionQueryRequest;
import com.example.batch.console.web.query.WorkerRegistryQueryRequest;
import com.example.batch.console.web.response.config.ConsoleConfigChangeLogResponse;
import com.example.batch.console.web.response.config.ConsoleConfigReleaseResponse;
import com.example.batch.console.web.response.ops.ConsoleAuditLogResponse;
import com.example.batch.console.web.response.ops.ConsoleOutboxDeliveryLogResponse;
import com.example.batch.console.web.response.ops.ConsoleOutboxRetryLogResponse;
import com.example.batch.console.web.response.ops.ConsoleSchedulerSnapshotHistoryResponse;
import com.example.batch.console.web.response.ops.ConsoleSchedulerSnapshotResponse;
import com.example.batch.console.web.response.ops.ConsoleSecretVersionResponse;
import com.example.batch.console.web.response.ops.ConsoleWorkerRegistryResponse;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * {@link com.example.batch.console.application.ConsoleReportExcelApplicationService} 的默认实现：
 * 委托查询/配置服务取数并生成通用 Excel 行集。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleReportExcelApplicationService
    implements ConsoleReportExcelApplicationService {

  private final ConsoleConfigApplicationService configApplicationService;
  private final ConsoleQueryApplicationService queryApplicationService;
  private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
  private final RestClient.Builder restClientBuilder;
  private final Environment environment;

  @Override
  public ResponseEntity<InputStreamResource> exportConfigReleases(
      ConfigReleaseQueryRequest request) {
    return exportRows(
        "config_releases",
        "config-releases",
        "config releases",
        configApplicationService.configReleases(request),
        ConsoleConfigReleaseResponse.class);
  }

  @Override
  public ResponseEntity<InputStreamResource> exportSecretVersions(
      SecretVersionQueryRequest request) {
    return exportRows(
        "secret_versions",
        "secret-versions",
        "secret versions",
        configApplicationService.secretVersions(request),
        ConsoleSecretVersionResponse.class);
  }

  @Override
  public ResponseEntity<InputStreamResource> exportConfigChangeLogs(
      ConfigChangeLogQueryRequest request) {
    return exportRows(
        "config_change_logs",
        "config-change-logs",
        "config change logs",
        configApplicationService.configChangeLogs(request),
        ConsoleConfigChangeLogResponse.class);
  }

  @Override
  public ResponseEntity<InputStreamResource> exportAuditLogs(AuditLogQueryRequest request) {
    return exportRows(
        "audit_logs",
        "audit-logs",
        "audit logs",
        queryApplicationService.auditLogs(request).items(),
        ConsoleAuditLogResponse.class);
  }

  @Override
  public ResponseEntity<InputStreamResource> exportSchedulerSnapshot(String tenantId) {
    ConsoleSchedulerSnapshotResponse snapshot = fetchSnapshot(tenantId);
    List<ConsoleSchedulerSnapshotResponse.PolicySnapshot> rows =
        snapshot == null ? List.of() : snapshot.policies();
    return exportRows(
        "scheduler_snapshot",
        "scheduler-snapshot",
        "scheduler snapshot",
        rows,
        ConsoleSchedulerSnapshotResponse.PolicySnapshot.class);
  }

  @Override
  public ResponseEntity<InputStreamResource> exportSchedulerSnapshotHistory(
      String tenantId, int limit) {
    List<ConsoleSchedulerSnapshotHistoryResponse> rows = fetchSnapshotHistory(tenantId, limit);
    return exportRows(
        "scheduler_snapshot_history",
        "scheduler-snapshot-history",
        "scheduler snapshot history",
        rows,
        ConsoleSchedulerSnapshotHistoryResponse.class);
  }

  @Override
  public ResponseEntity<InputStreamResource> exportWorkers(WorkerRegistryQueryRequest request) {
    return exportRows(
        "workers",
        "workers",
        "workers",
        queryApplicationService.workers(request).items(),
        ConsoleWorkerRegistryResponse.class);
  }

  @Override
  public ResponseEntity<InputStreamResource> exportOutboxRetries(
      OutboxRetryLogQueryRequest request) {
    return exportRows(
        "outbox_retries",
        "outbox-retries",
        "outbox retries",
        queryApplicationService.outboxRetries(request).items(),
        ConsoleOutboxRetryLogResponse.class);
  }

  @Override
  public ResponseEntity<InputStreamResource> exportOutboxDeliveries(
      OutboxDeliveryLogQueryRequest request) {
    return exportRows(
        "outbox_deliveries",
        "outbox-deliveries",
        "outbox deliveries",
        queryApplicationService.outboxDeliveries(request).items(),
        ConsoleOutboxDeliveryLogResponse.class);
  }

  private ConsoleSchedulerSnapshotResponse fetchSnapshot(String tenantId) {
    RestClient client =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    return client
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/internal/scheduler/snapshot")
                    .queryParam("tenantId", tenantId)
                    .build())
        .retrieve()
        .body(ConsoleSchedulerSnapshotResponse.class);
  }

  private List<ConsoleSchedulerSnapshotHistoryResponse> fetchSnapshotHistory(
      String tenantId, int limit) {
    RestClient client =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    List<ConsoleSchedulerSnapshotHistoryResponse> body =
        client
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder
                        .path("/internal/scheduler/snapshot/history")
                        .queryParam("tenantId", tenantId)
                        .queryParam("limit", limit)
                        .build())
            .retrieve()
            .body(
                new ParameterizedTypeReference<List<ConsoleSchedulerSnapshotHistoryResponse>>() {});
    return body == null ? List.of() : body;
  }

  private <T> ResponseEntity<InputStreamResource> exportRows(
      String sheetName, String filePrefix, String title, List<T> rows, Class<T> rowType) {
    byte[] workbookBytes = writeWorkbook(sheetName, title, rows, rowType);
    InputStreamResource body = new InputStreamResource(new ByteArrayInputStream(workbookBytes));
    String fileName = filePrefix + "-" + Instant.now().toEpochMilli() + ".xlsx";
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(fileName).build().toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(body);
  }

  private <T> byte[] writeWorkbook(String sheetName, String title, List<T> rows, Class<T> rowType) {
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet dataSheet = workbook.createSheet(sheetName);
      dataSheet.createFreezePane(0, 1, 0, 1);
      CellStyle headerStyle = createHeaderStyle(workbook);
      List<String> headers = extractHeaders(rowType, rows);
      writeHeaders(dataSheet, headers, headerStyle);
      int rowIndex = 1;
      for (T row : rows) {
        Row excelRow = dataSheet.createRow(rowIndex++);
        List<Object> values = extractValues(row, headers);
        for (int i = 0; i < values.size(); i++) {
          writeCell(excelRow, i, values.get(i));
        }
      }
      setWidths(dataSheet, headers);
      createReadmeSheet(workbook, title);
      workbook.write(out);
      return out.toByteArray();
    } catch (Exception exception) {
      throw BizException.of(
          ResultCode.SYSTEM_ERROR,
          "error.common.system_error_detail",
          exception,
          "导出报表生成失败:" + exception.getMessage());
    }
  }

  private List<String> extractHeaders(Class<?> rowType, List<?> rows) {
    if (rowType.isRecord()) {
      RecordComponent[] components = rowType.getRecordComponents();
      List<String> headers = new ArrayList<>();
      for (RecordComponent component : components) {
        headers.add(component.getName());
      }
      return headers;
    }
    if (!rows.isEmpty() && rows.get(0) instanceof Map<?, ?> map) {
      return new ArrayList<>(map.keySet().stream().map(String::valueOf).toList());
    }
    try {
      return Arrays.stream(Introspector.getBeanInfo(rowType, Object.class).getPropertyDescriptors())
          .map(PropertyDescriptor::getName)
          .filter(name -> !"class".equals(name))
          .sorted()
          .collect(Collectors.toCollection(ArrayList::new));
    } catch (Exception exception) {
      throw BizException.of(
          ResultCode.SYSTEM_ERROR,
          "error.common.system_error_detail",
          exception,
          "导出报表表头解析失败:" + exception.getMessage());
    }
  }

  private List<Object> extractValues(Object row, List<String> headers) {
    if (row instanceof Map<?, ?> map) {
      List<Object> values = new ArrayList<>();
      for (String header : headers) {
        values.add(map.get(header));
      }
      return values;
    }
    Class<?> rowType = row.getClass();
    if (rowType.isRecord()) {
      List<Object> values = new ArrayList<>();
      try {
        for (String header : headers) {
          RecordComponent component =
              Arrays.stream(rowType.getRecordComponents())
                  .filter(item -> item.getName().equals(header))
                  .findFirst()
                  .orElse(null);
          values.add(component == null ? null : component.getAccessor().invoke(row));
        }
      } catch (Exception exception) {
        throw BizException.of(
            ResultCode.SYSTEM_ERROR,
            "error.common.system_error_detail",
            exception,
            "导出报表 record 字段读取失败:" + exception.getMessage());
      }
      return values;
    }
    List<Object> values = new ArrayList<>();
    try {
      BeanInfo beanInfo = Introspector.getBeanInfo(rowType, Object.class);
      Map<String, PropertyDescriptor> descriptors = new LinkedHashMap<>();
      for (var descriptor : beanInfo.getPropertyDescriptors()) {
        descriptors.put(descriptor.getName(), descriptor);
      }
      for (String header : headers) {
        PropertyDescriptor descriptor = descriptors.get(header);
        values.add(
            descriptor == null || descriptor.getReadMethod() == null
                ? null
                : descriptor.getReadMethod().invoke(row));
      }
    } catch (Exception exception) {
      throw BizException.of(
          ResultCode.SYSTEM_ERROR,
          "error.common.system_error_detail",
          exception,
          "导出报表 bean 字段读取失败:" + exception.getMessage());
    }
    return values;
  }

  private void createReadmeSheet(Workbook workbook, String title) {
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    setReadmeColumnWidth(sheet);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] lines = {
      title + " 导出报表",
      "1. 本工作簿仅供导出,不接受回传上传。",
      "2. 第一个 sheet 为报表数据。",
      "3. 列结构与当前 API 响应模型对齐。",
      "4. 导出数据可由下游消费者过滤与归档。"
    };
    for (int i = 0; i < lines.length; i++) {
      Row row = sheet.createRow(i);
      row.createCell(0).setCellValue(lines[i]);
      if (i == 0) {
        row.getCell(0).setCellStyle(titleStyle);
      }
    }
  }

  private String resolveUrl(String url) {
    return environment.resolvePlaceholders(url);
  }
}
