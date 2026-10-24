package io.github.pinpols.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("配置包工作簿结构安全限制")
class ConfigPackageWorkbookLimitsTest {

  @Test
  @DisplayName("接受未超过结构上限的工作簿")
  void shouldAcceptWorkbook_whenAtConfiguredLimits() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("config");
      sheet
          .createRow(ConfigPackageWorkbookLimits.MAX_ROW_INDEX)
          .createCell(ConfigPackageWorkbookLimits.MAX_COLUMNS - 1)
          .setCellValue("x".repeat(ConfigPackageWorkbookLimits.MAX_CELL_TEXT_LENGTH));

      assertThatCode(() -> ConfigPackageWorkbookLimits.validate(workbook))
          .doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("工作表数量超过上限时拒绝工作簿")
  void shouldRejectWorkbook_whenSheetCountExceedsLimit() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      for (int index = 0; index <= ConfigPackageWorkbookLimits.MAX_SHEETS; index++) {
        workbook.createSheet("sheet-" + index);
      }

      assertRejected(workbook, "too many sheets");
    }
  }

  @Test
  @DisplayName("行索引超过上限时拒绝工作簿")
  void shouldRejectWorkbook_whenRowIndexExceedsLimit() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      workbook
          .createSheet("config")
          .createRow(ConfigPackageWorkbookLimits.MAX_ROW_INDEX + 1)
          .createCell(0)
          .setCellValue("out of bounds");

      assertRejected(workbook, "maximum row index");
    }
  }

  @Test
  @DisplayName("物理总行数超过上限时拒绝工作簿")
  void shouldRejectWorkbook_whenPhysicalRowCountExceedsLimit() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      for (int sheetIndex = 0; sheetIndex < 2; sheetIndex++) {
        Sheet sheet = workbook.createSheet("config-" + sheetIndex);
        for (int index = 0; index <= ConfigPackageWorkbookLimits.MAX_ROW_INDEX; index++) {
          sheet.createRow(index).createCell(0).setCellValue("x");
        }
      }

      assertRejected(workbook, "too many rows");
    }
  }

  @Test
  @DisplayName("列索引超过上限时拒绝工作簿")
  void shouldRejectWorkbook_whenColumnIndexExceedsLimit() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      workbook
          .createSheet("config")
          .createRow(0)
          .createCell(ConfigPackageWorkbookLimits.MAX_COLUMNS)
          .setCellValue("out of bounds");

      assertRejected(workbook, "maximum column index");
    }
  }

  @Test
  @DisplayName("解析前配置 POI 压缩包展开限制")
  void shouldConfigureZipExpansionGuards_beforeWorkbookParsing() {
    ConfigPackageWorkbookLimits.configurePoiZipLimits();

    assertThat(ZipSecureFile.getMaxEntrySize()).isEqualTo(64L * 1024 * 1024);
    assertThat(ZipSecureFile.getMaxFileCount()).isEqualTo(128);
    assertThat(ZipSecureFile.getMinInflateRatio()).isEqualTo(0.01d);
  }

  private static void assertRejected(XSSFWorkbook workbook, String reason) {
    assertThatThrownBy(() -> ConfigPackageWorkbookLimits.validate(workbook))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getMessageArgs())
                .anySatisfy(argument -> assertThat(argument.toString()).contains(reason)));
  }
}
