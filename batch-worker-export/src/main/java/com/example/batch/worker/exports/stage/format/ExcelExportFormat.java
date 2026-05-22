package com.example.batch.worker.exports.stage.format;

import com.example.batch.common.plugin.ExportDataPlugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/** 生成 Excel（.xlsx）文件，使用流式 SXSSF 工作簿以控制大数据量写入时的堆内存占用。 */
@Component
public class ExcelExportFormat extends AbstractExportFormat {

  public ExcelExportFormat(ObjectMapper objectMapper) {
    super(objectMapper);
  }

  @Override
  public String formatType() {
    return "EXCEL";
  }

  @Override
  public long generate(ExportFormatContext ctx) throws Exception {
    Long batchIdLong = ctx.batchId() == null ? null : Long.valueOf(String.valueOf(ctx.batchId()));
    ExportDataPlugin.DetailPage firstPage =
        ctx.dataPlugin().loadDetailPage(ctx.dataCtx(), batchIdLong, ctx.pageSize(), null);
    List<ColumnLayout> columns =
        resolveExcelColumns(ctx.dataCtx(), ctx.dataPlugin(), ctx.batch(), firstPage.rows());
    DelimitedFormatConfig formatConfig =
        resolveDelimitedFormatConfig(ctx.dataCtx().templateConfig());

    SXSSFWorkbook workbook = new SXSSFWorkbook(100);
    try (OutputStream outputStream =
        Files.newOutputStream(
            ctx.generatedFile(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      Sheet sheet = workbook.createSheet(resolveSheetName(ctx.dataCtx().templateConfig()));
      int[] rowNoHolder = {0};
      rowNoHolder[0] =
          writeExcelHeaderRows(sheet, columns, rowNoHolder[0], formatConfig.headerRows());
      long recordCount =
          generatePaged(
              ctx,
              firstPage,
              (batch, detail, rowIndex) -> {
                Row row = sheet.createRow(rowNoHolder[0]++);
                for (int i = 0; i < columns.size(); i++) {
                  Cell cell = row.createCell(i);
                  cell.setCellValue(
                      textValue(resolveDelimitedValue(batch, detail, columns.get(i).source())));
                }
              });
      workbook.write(outputStream);
      return recordCount;
    } finally {
      // POI 5.x 起 SXSSFWorkbook.close() 会自动清理 /tmp 下的 sheet-backing temp files,
      // dispose() 已被标记 deprecated 且功能合并入 close(),因此只需 close()。
      workbook.close();
    }
  }

  private int writeExcelHeaderRows(
      Sheet sheet, List<ColumnLayout> columns, int rowNo, int headerRows) {
    int effectiveHeaderRows = Math.max(1, headerRows);
    for (int i = 0; i < effectiveHeaderRows; i++) {
      Row row = sheet.createRow(rowNo++);
      for (int c = 0; c < columns.size(); c++) {
        row.createCell(c).setCellValue(columns.get(c).header());
      }
    }
    return rowNo;
  }
}
