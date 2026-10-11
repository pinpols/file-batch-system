package io.github.pinpols.batch.console.infrastructure.excel;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

/** 租户配置包的结构边界；POI ZIP 阈值为进程级设置，适用于本服务的所有 OOXML 读取。 */
public final class ConfigPackageWorkbookLimits {

  public static final int MAX_SHEETS = 24;
  public static final int MAX_ROW_INDEX = 10_000;
  public static final int MAX_TOTAL_ROWS = 20_000;
  public static final int MAX_COLUMNS = 128;
  public static final int MAX_CELL_TEXT_LENGTH = 32_767;

  private static final long MAX_ZIP_ENTRY_BYTES = 64L * 1024 * 1024;
  private static final long MAX_ZIP_ENTRIES = 128;
  private static final double MIN_ZIP_INFLATE_RATIO = 0.01d;

  private ConfigPackageWorkbookLimits() {}

  /** 必须在 POI 打开工作簿前调用；POI 的 ZIP 防护阈值是进程级设置。 */
  public static void configurePoiZipLimits() {
    ZipSecureFile.setMaxEntrySize(MAX_ZIP_ENTRY_BYTES);
    ZipSecureFile.setMaxFileCount(MAX_ZIP_ENTRIES);
    ZipSecureFile.setMinInflateRatio(MIN_ZIP_INFLATE_RATIO);
  }

  public static void validate(Workbook workbook) {
    if (workbook.getNumberOfSheets() > MAX_SHEETS) {
      reject("workbook contains too many sheets");
    }

    int totalRows = 0;
    for (Sheet sheet : workbook) {
      if (sheet.getLastRowNum() > MAX_ROW_INDEX) {
        reject("sheet exceeds the maximum row index: " + sheet.getSheetName());
      }
      totalRows += sheet.getPhysicalNumberOfRows();
      if (totalRows > MAX_TOTAL_ROWS) {
        reject("workbook contains too many rows");
      }
      validateRows(sheet);
    }
  }

  private static void validateRows(Sheet sheet) {
    for (Row row : sheet) {
      if (row.getLastCellNum() > MAX_COLUMNS) {
        reject("row exceeds the maximum column index: " + sheet.getSheetName());
      }
      for (Cell cell : row) {
        if (cell.getCellType() == CellType.STRING
            && cell.getStringCellValue().length() > MAX_CELL_TEXT_LENGTH) {
          reject("cell text exceeds the maximum length: " + sheet.getSheetName());
        }
      }
    }
  }

  private static void reject(String message) {
    throw BizException.of(
        ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey(), message);
  }
}
