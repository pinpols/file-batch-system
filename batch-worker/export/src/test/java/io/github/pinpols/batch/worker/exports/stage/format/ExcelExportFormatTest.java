package io.github.pinpols.batch.worker.exports.stage.format;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.plugin.ExportDataContext;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ExcelExportFormat 类型化写入 / 多 sheet 拆分 / 表头样式 + 向后兼容验证。 */
class ExcelExportFormatTest {

  private final ExcelExportFormat format = new ExcelExportFormat(new ObjectMapper());

  @TempDir Path tempDir;

  @Test
  @DisplayName("不配 template = 历史行为:全文本、单 sheet、无类型/样式")
  void backwardCompatible_allTextSingleSheet_whenNoConfig() throws Exception {
    List<Map<String, Object>> rows = List.of(rowOf("amount", "100", "name", "甲"));
    Path file = tempDir.resolve("plain.xlsx");

    format.generate(ctx(file, Map.of(), rows));

    try (Workbook wb = open(file)) {
      assertThat(wb.getNumberOfSheets()).isEqualTo(1);
      Sheet sheet = wb.getSheetAt(0);
      // 行 0 表头,行 1 数据;amount 即使是数字串也按文本写。
      Cell amountCell = sheet.getRow(1).getCell(0);
      assertThat(amountCell.getCellType()).isEqualTo(CellType.STRING);
      assertThat(amountCell.getStringCellValue()).isEqualTo("100");
    }
  }

  @Test
  @DisplayName("type=NUMBER/DATE/BOOL 按真类型写,带 format,解析失败回退文本")
  void writesTypedCells_whenColumnTypeDeclared() throws Exception {
    Map<String, Object> templateConfig =
        Map.of(
            "csv_columns",
            List.of(
                Map.of(
                    "source",
                    "amount",
                    "header",
                    "金额",
                    "type",
                    "NUMBER",
                    "numberFormat",
                    "#,##0.00"),
                Map.of("source", "ts", "header", "日期", "type", "DATE", "dateFormat", "yyyy-MM-dd"),
                Map.of("source", "flag", "header", "启用", "type", "BOOL"),
                Map.of("source", "bad", "header", "坏数字", "type", "NUMBER")));
    List<Map<String, Object>> rows =
        List.of(
            rowOf(
                "amount", "1234567.5",
                "ts", "2026-06-11",
                "flag", "true",
                "bad", "not-a-number"));
    Path file = tempDir.resolve("typed.xlsx");

    format.generate(ctx(file, templateConfig, rows));

    try (Workbook wb = open(file)) {
      Sheet sheet = wb.getSheetAt(0);
      Cell number = sheet.getRow(1).getCell(0);
      assertThat(number.getCellType()).isEqualTo(CellType.NUMERIC);
      assertThat(number.getNumericCellValue()).isEqualTo(1234567.5);
      assertThat(number.getCellStyle().getDataFormatString()).contains("#,##0.00");

      Cell date = sheet.getRow(1).getCell(1);
      assertThat(date.getCellType()).isEqualTo(CellType.NUMERIC);
      assertThat(DateUtil.isCellDateFormatted(date)).isTrue();

      Cell bool = sheet.getRow(1).getCell(2);
      assertThat(bool.getCellType()).isEqualTo(CellType.BOOLEAN);
      assertThat(bool.getBooleanCellValue()).isTrue();

      // 类型声明 NUMBER 但值不可解析 → 回退文本,不中断导出。
      Cell bad = sheet.getRow(1).getCell(3);
      assertThat(bad.getCellType()).isEqualTo(CellType.STRING);
      assertThat(bad.getStringCellValue()).isEqualTo("not-a-number");
    }
  }

  @Test
  @DisplayName("rows_per_sheet 达阈值自动拆分多 sheet,每 sheet 复制表头")
  void splitsIntoMultipleSheets_whenRowsPerSheetReached() throws Exception {
    Map<String, Object> templateConfig = Map.of("rows_per_sheet", 2, "sheet_name", "数据");
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      rows.add(rowOf("id", String.valueOf(i)));
    }
    Path file = tempDir.resolve("multi.xlsx");

    format.generate(ctx(file, templateConfig, rows));

    try (Workbook wb = open(file)) {
      // 5 行 / 每 sheet 2 行 = 3 个 sheet(2 + 2 + 1)。
      assertThat(wb.getNumberOfSheets()).isEqualTo(3);
      assertThat(wb.getSheetName(0)).isEqualTo("数据");
      assertThat(wb.getSheetName(1)).isEqualTo("数据_2");
      assertThat(wb.getSheetName(2)).isEqualTo("数据_3");
      // 每个 sheet 第 0 行都是表头。
      for (int s = 0; s < 3; s++) {
        assertThat(wb.getSheetAt(s).getRow(0).getCell(0).getStringCellValue()).isEqualTo("id");
      }
      // 第三个 sheet 只有 1 行数据(行 1),无行 2。
      assertThat(wb.getSheetAt(2).getRow(2)).isNull();
    }
  }

  @Test
  @DisplayName("header_style 加粗/背景/冻结/自适应列宽 + header_groups 合并分组表头(仅第 0 行)")
  void appliesHeaderStyleAndMergedGroups_whenConfigured() throws Exception {
    Map<String, Object> templateConfig =
        Map.of(
            "csv_columns",
                List.of(Map.of("source", "a", "header", "A"), Map.of("source", "b", "header", "B")),
            "header_style",
                Map.of(
                    "bold", true,
                    "background", "#1F4E78",
                    "freeze_header", true,
                    "auto_width", true),
            "header_groups", List.of(Map.of("title", "分组", "from", 0, "to", 1)));
    List<Map<String, Object>> rows = List.of(rowOf("a", "1", "b", "2"));
    Path file = tempDir.resolve("styled.xlsx");

    format.generate(ctx(file, templateConfig, rows));

    try (Workbook wb = open(file)) {
      Sheet sheet = wb.getSheetAt(0);
      // 第 0 行 = 分组合并表头,第 1 行 = 列表头,第 2 行 = 数据。
      assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("分组");
      assertThat(sheet.getNumMergedRegions()).isEqualTo(1);
      assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("A");
      // 表头加粗。
      XSSFCellStyle headerStyle = (XSSFCellStyle) sheet.getRow(1).getCell(0).getCellStyle();
      assertThat(headerStyle.getFont().getBold()).isTrue();
      // 冻结窗格存在。
      assertThat(sheet.getPaneInformation()).isNotNull();
      assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("1");
    }
  }

  @Test
  @DisplayName("公式注入值被前缀单引号转义")
  void escapesFormulaInjection() throws Exception {
    List<Map<String, Object>> rows = List.of(rowOf("v", "=SUM(A1:A2)"));
    Path file = tempDir.resolve("inject.xlsx");

    format.generate(ctx(file, Map.of(), rows));

    try (Workbook wb = open(file)) {
      assertThat(wb.getSheetAt(0).getRow(1).getCell(0).getStringCellValue())
          .isEqualTo("'=SUM(A1:A2)");
    }
  }

  private Workbook open(Path file) throws Exception {
    return new XSSFWorkbook(Files.newInputStream(file));
  }

  private Map<String, Object> rowOf(String... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  private ExportFormatContext ctx(
      Path file, Map<String, Object> templateConfig, List<Map<String, Object>> rows) {
    ExportDataContext dataCtx =
        new ExportDataContext("t1", "job", "B1", "tpl", templateConfig, Map.of());
    ExportDataPlugin plugin =
        new ExportDataPlugin() {
          @Override
          public String id() {
            return "test";
          }

          @Override
          public Map<String, Object> loadBatch(ExportDataContext context) {
            return Map.of();
          }

          @Override
          public DetailPage loadDetailPage(
              ExportDataContext context, Long batchId, int pageSize, Object cursor) {
            // 单页返回全部行,cursor=null 表示无后继页(Excel 不续跑)。
            if (cursor != null) {
              return DetailPage.empty();
            }
            return new DetailPage(rows, null);
          }
        };
    return ExportFormatContext.builder()
        .batch(Map.of())
        .batchId(1L)
        .pageSize(1000)
        .chunkSize(0)
        .generatedFile(file)
        .dataPlugin(plugin)
        .dataCtx(dataCtx)
        .checkpoint(null)
        .build();
  }
}
