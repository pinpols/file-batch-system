package io.github.pinpols.batch.console.domain.rbac.service;

import static io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserBatchProvisioningService.HEADERS;
import static io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserBatchProvisioningService.MAX_ROWS;
import static io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserBatchProvisioningService.invalid;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserBatchProvisioningService.AccountRow;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** 解析并校验批量开户工作簿，供预览流程使用。 */
final class ConsoleUserBatchWorkbookParser {
  private ConsoleUserBatchWorkbookParser() {}

  static List<AccountRow> parse(byte[] bytes) {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      if (!(workbook instanceof XSSFWorkbook xssf)
          || xssf.getPackagePart()
              .getRelationshipsByType(XSSFRelation.EXTERNAL_LINKS.getRelation())
              .iterator()
              .hasNext()) {
        throw invalid("Only XLSX without external links is supported");
      }
      if (workbook.getNumberOfSheets() != 1 || !"Accounts".equals(workbook.getSheetName(0))) {
        throw invalid("Expected one Accounts sheet");
      }
      Sheet sheet = workbook.getSheetAt(0);
      if (sheet.getLastRowNum() > 5000) throw invalid("Workbook has too many rows");
      DataFormatter formatter = new DataFormatter(Locale.ROOT);
      validateHeader(sheet, formatter);
      return parseRows(sheet, formatter);
    } catch (IOException | InvalidFormatException ex) {
      throw invalid("Invalid XLSX workbook");
    }
  }

  private static void validateHeader(Sheet sheet, DataFormatter formatter) {
    Row header = sheet.getRow(0);
    if (EmptyChecks.isNull(header) || header.getLastCellNum() != HEADERS.size())
      throw invalid("Invalid header");
    for (int i = 0; i < HEADERS.size(); i++) {
      if (!HEADERS.get(i).equals(text(header.getCell(i), formatter)))
        throw invalid("Invalid header");
    }
  }

  private static List<AccountRow> parseRows(Sheet sheet, DataFormatter formatter) {
    List<AccountRow> rows = new ArrayList<>();
    for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (EmptyChecks.isNotNull(row)) {
        AccountRow account = parseRow(row, rowIndex + 1, formatter);
        if (EmptyChecks.isNotNull(account)) {
          if (rows.size() == MAX_ROWS) throw invalid("Maximum 500 accounts per batch");
          rows.add(account);
        }
      }
    }
    return rows;
  }

  private static AccountRow parseRow(Row row, int rowNo, DataFormatter formatter) {
    if (row.getLastCellNum() > HEADERS.size()) throw invalid("Unexpected columns at row " + rowNo);
    List<String> cells = new ArrayList<>();
    for (int col = 0; col < HEADERS.size(); col++) {
      Cell cell = row.getCell(col);
      String cellText = text(cell, formatter);
      cells.add(cellText);
    }
    boolean allCellsBlank = cells.stream().allMatch(String::isBlank);
    if (allCellsBlank) return null;
    return new AccountRow(rowNo, cells.get(0), cells.get(1), cells.get(2), cells.get(3));
  }

  private static String text(Cell cell, DataFormatter formatter) {
    if (EmptyChecks.isNull(cell)) return "";
    if (cell.getCellType() == CellType.FORMULA) throw invalid("Formula cells are not allowed");
    String value = formatter.formatCellValue(cell).trim();
    if (value.length() > 256) throw invalid("Cell value exceeds 256 characters");
    return value;
  }
}
