package com.example.batch.console.infrastructure.excel;

import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.COL_DESCRIPTION;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.COL_EDGE_TYPE;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.COL_ENABLED;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.COL_NODE_TYPE;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.COL_RETRY_POLICY;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.COL_TENANT_ID;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.COL_WORKFLOW_CODE;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.COL_WORKFLOW_TYPE;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.COL_WORKFLOW_VERSION;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.DEF_COLUMNS;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.DEF_HEADERS;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.DEF_SHEET;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.EDGE_COLUMNS;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.EDGE_HEADERS;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.EDGE_SHEET;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.NODE_COLUMNS;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.NODE_HEADERS;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.NODE_SHEET;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelTextUtils.normalize;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelTextUtils.normalizeEnum;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelTextUtils.parseBoolean;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelTextUtils.parseInteger;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelTextUtils.tenantOrDefault;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.enums.WorkflowEdgeType;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.CodeNormalizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowDefinitionPayload;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowDefinitionRow;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowEdgeIdentity;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowEdgePayload;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowEdgeRow;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowIdentity;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowNodeExecution;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowNodeIdentity;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowNodeRelation;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowNodeRow;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowNodeRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * P2-3 god-class-decomposition extract: 把 byte[] xlsx 反序列化成业务行的 sheet parser。
 *
 * <p>覆盖原 service ~250 行解析逻辑:三个 sheet 各自解析 + readSheetRows 模板 + header 校验 + 单元格读取 + row blank 判定 +
 * 文件名兜底。所有出口都是不可变 record 集合,parser 自身无状态。
 */
@Component
public class WorkflowExcelSheetParser {

  private static final Set<String> WORKFLOW_TYPES = DictEnum.codes(WorkflowType.class);
  private static final Set<String> NODE_TYPES = DictEnum.codes(WorkflowNodeType.class);
  private static final Set<String> RETRY_POLICIES = DictEnum.codes(RetryPolicyType.class);
  private static final Set<String> EDGE_TYPES = DictEnum.codes(WorkflowEdgeType.class);

  /**
   * 把 xlsx 字节流反序列化为定义/节点/边三类 row。{@code originalFileName} 作为 audit/UI 显示用,空白时回退默认值。 任何 IO / 结构异常折叠为
   * {@link BizException} 返回给前端。
   */
  public ParsedWorkbook parseWorkbook(byte[] bytes, String tenantId, String originalFileName)
      throws IOException {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      if (workbook.getNumberOfSheets() == 0) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.excel.no_sheet");
      }
      List<WorkflowDefinitionRow> definitions =
          parseDefinitionSheet(findSheet(workbook, DEF_SHEET), tenantId);
      List<WorkflowNodeRow> nodes = parseNodeSheet(findSheet(workbook, NODE_SHEET), tenantId);
      List<WorkflowEdgeRow> edges = parseEdgeSheet(findSheet(workbook, EDGE_SHEET), tenantId);
      return new ParsedWorkbook(
          fileNameOrDefault(originalFileName), tenantId, definitions, nodes, edges);
    } catch (BizException exception) {
      throw exception;
    } catch (Exception exception) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "failed to read excel workbook: " + exception.getMessage());
    }
  }

  private Sheet findSheet(Workbook workbook, String sheetName) {
    Sheet sheet = workbook.getSheet(sheetName);
    Guard.require(sheet != null, "excel sheet missing: " + sheetName);
    return sheet;
  }

  private List<WorkflowDefinitionRow> parseDefinitionSheet(Sheet sheet, String tenantId) {
    List<WorkflowDefinitionRow> rows = new ArrayList<>();
    for (SheetRow rowData : readSheetRows(sheet, DEF_COLUMNS, DEF_HEADERS)) {
      Map<String, String> rowValues = rowData.values();
      rows.add(
          new WorkflowDefinitionRow(
              new WorkflowIdentity(
                  rowData.rowNo(),
                  tenantOrDefault(rowValues.get(COL_TENANT_ID), tenantId),
                  normalize(rowValues.get(COL_WORKFLOW_CODE))),
              new WorkflowDefinitionPayload(
                  normalize(rowValues.get("workflow_name")),
                  normalizeEnum(rowValues.get(COL_WORKFLOW_TYPE), WORKFLOW_TYPES),
                  parseInteger(rowValues.get("version")),
                  parseBoolean(rowValues.get(COL_ENABLED), true),
                  normalize(rowValues.get(COL_DESCRIPTION)))));
    }
    return rows;
  }

  private List<WorkflowNodeRow> parseNodeSheet(Sheet sheet, String tenantId) {
    List<WorkflowNodeRow> rows = new ArrayList<>();
    for (SheetRow rowData : readSheetRows(sheet, NODE_COLUMNS, NODE_HEADERS)) {
      Map<String, String> rowValues = rowData.values();
      rows.add(
          new WorkflowNodeRow(
              new WorkflowNodeIdentity(
                  rowData.rowNo(),
                  tenantOrDefault(rowValues.get(COL_TENANT_ID), tenantId),
                  normalize(rowValues.get(COL_WORKFLOW_CODE)),
                  parseInteger(rowValues.get(COL_WORKFLOW_VERSION)),
                  normalize(rowValues.get("node_code"))),
              new WorkflowNodeRelation(
                  normalize(rowValues.get("node_name")),
                  normalizeEnum(rowValues.get(COL_NODE_TYPE), NODE_TYPES),
                  normalize(rowValues.get("related_job_code")),
                  normalize(rowValues.get("related_pipeline_code"))),
              new WorkflowNodeExecution(
                  CodeNormalizer.toUpperOrNull(rowValues.get("worker_group")),
                  CodeNormalizer.toConfigFormOrNull(rowValues.get("window_code")),
                  parseInteger(rowValues.get("node_order"))),
              new WorkflowNodeRuntime(
                  normalizeEnum(rowValues.get(COL_RETRY_POLICY), RETRY_POLICIES),
                  parseInteger(rowValues.get("retry_max_count")),
                  parseInteger(rowValues.get("timeout_seconds")),
                  normalize(rowValues.get("node_params")),
                  parseBoolean(rowValues.get(COL_ENABLED), true))));
    }
    return rows;
  }

  private List<WorkflowEdgeRow> parseEdgeSheet(Sheet sheet, String tenantId) {
    List<WorkflowEdgeRow> rows = new ArrayList<>();
    for (SheetRow rowData : readSheetRows(sheet, EDGE_COLUMNS, EDGE_HEADERS)) {
      Map<String, String> rowValues = rowData.values();
      rows.add(
          new WorkflowEdgeRow(
              new WorkflowEdgeIdentity(
                  rowData.rowNo(),
                  tenantOrDefault(rowValues.get(COL_TENANT_ID), tenantId),
                  normalize(rowValues.get(COL_WORKFLOW_CODE)),
                  parseInteger(rowValues.get(COL_WORKFLOW_VERSION)),
                  normalize(rowValues.get("from_node_code")),
                  normalize(rowValues.get("to_node_code"))),
              new WorkflowEdgePayload(
                  normalizeEnum(rowValues.get(COL_EDGE_TYPE), EDGE_TYPES),
                  normalize(rowValues.get("condition_expr")),
                  parseBoolean(rowValues.get(COL_ENABLED), true))));
    }
    return rows;
  }

  private List<SheetRow> readSheetRows(
      Sheet sheet, List<String> columns, Set<String> requiredHeaders) {
    DataFormatter formatter = new DataFormatter();
    Row headerRow = sheet.getRow(sheet.getFirstRowNum());
    if (headerRow == null) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "excel header row is missing for sheet: " + sheet.getSheetName());
    }
    Map<String, Integer> headerIndex = readHeaderIndex(headerRow, formatter);
    validateHeaders(sheet.getSheetName(), headerIndex, requiredHeaders);
    List<SheetRow> rows = new ArrayList<>();
    for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null || rowIsBlank(row, formatter)) {
        continue;
      }
      Map<String, String> rowValues = new LinkedHashMap<>();
      for (String header : columns) {
        Integer columnIndex = headerIndex.get(header);
        rowValues.put(header, normalize(cellText(row, columnIndex, formatter)));
      }
      rows.add(new SheetRow(row.getRowNum() + 1, rowValues));
    }
    return rows;
  }

  private Map<String, Integer> readHeaderIndex(Row headerRow, DataFormatter formatter) {
    Map<String, Integer> headers = new LinkedHashMap<>();
    for (int cellIndex = headerRow.getFirstCellNum();
        cellIndex < headerRow.getLastCellNum();
        cellIndex++) {
      Cell cell = headerRow.getCell(cellIndex);
      String header = normalize(formatter.formatCellValue(cell));
      if (Texts.hasText(header)) {
        headers.put(header, cellIndex);
      }
    }
    return headers;
  }

  private void validateHeaders(
      String sheetName, Map<String, Integer> headerIndex, Set<String> requiredHeaders) {
    Set<String> missing = new LinkedHashSet<>(requiredHeaders);
    missing.removeAll(headerIndex.keySet());
    if (!missing.isEmpty()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "excel header missing for sheet " + sheetName + ": " + String.join(", ", missing));
    }
  }

  private boolean rowIsBlank(Row row, DataFormatter formatter) {
    for (int cellIndex = row.getFirstCellNum(); cellIndex < row.getLastCellNum(); cellIndex++) {
      String value = normalize(formatter.formatCellValue(row.getCell(cellIndex)));
      if (Texts.hasText(value)) {
        return false;
      }
    }
    return true;
  }

  private String cellText(Row row, Integer columnIndex, DataFormatter formatter) {
    if (columnIndex == null) {
      return null;
    }
    Cell cell = row.getCell(columnIndex);
    return cell == null ? null : formatter.formatCellValue(cell);
  }

  private String fileNameOrDefault(String fileName) {
    if (!Texts.hasText(fileName)) {
      return "workflow-maintenance.xlsx";
    }
    return fileName;
  }

  /** xlsx 反序列化结果(file 名 + tenant + 三类 row)。 */
  public record ParsedWorkbook(
      String fileName,
      String tenantId,
      List<WorkflowDefinitionRow> definitions,
      List<WorkflowNodeRow> nodes,
      List<WorkflowEdgeRow> edges) {}

  /** 单行解析中间体:rowNo(1-based,匹配 Excel 行号) + columns→values 映射。 */
  private record SheetRow(int rowNo, Map<String, String> values) {}
}
