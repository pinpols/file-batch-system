package io.github.pinpols.batch.worker.exports.stage.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.OwnerOnlyFiles;
import io.github.pinpols.batch.common.utils.PrivateTempFiles;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.apache.poi.util.TempFile;
import org.apache.poi.util.TempFileCreationStrategy;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * 生成 Excel(.xlsx)文件,使用流式 SXSSF 工作簿以控制大数据量写入时的堆内存占用。
 *
 * <p>三项可选增强(均向后兼容,不配=历史全文本 / 单 sheet / 无样式):
 *
 * <ul>
 *   <li><b>类型化写入</b>:列声明 {@code type=NUMBER/DATE/BOOL} 时按真类型 {@code setCellValue},数字可求和、日期可筛选、
 *       大数字不再变科学计数法;{@code numberFormat} / {@code dateFormat} 走按列缓存的 {@link
 *       org.apache.poi.ss.usermodel.CellStyle}(SXSSF 下 cell style 有数量上限,绝不每 cell
 *       新建)。解析失败回退文本,不中断导出。
 *   <li><b>多 sheet 自动拆分</b>:{@code template_config.rows_per_sheet} 达阈值滚下一个 sheet(名带序号),每 sheet
 *       复制表头。 规避 .xlsx 单 sheet 1048576 行硬限。Excel 格式本就不参与 ADR-038 字节位点续跑(workbook 末尾整体 write、
 *       checkpoint 恒为 null,见 {@code ExportFormatContext}),故多 sheet 与续跑无交叉,不破坏续跑位点。
 *   <li><b>表头样式</b>:{@code template_config.header_style.bold/background/freeze_header/auto_width} +
 *       可选 {@code header_groups}(分组合并表头)。合并仅作用于第 0 行表头区(SXSSF 当前窗口内),不跨数据行,避开 SXSSF 合并窗口限制。
 * </ul>
 */
@Component
public class ExcelExportFormat extends AbstractExportFormat {

  private static final TempFileCreationStrategy PRIVATE_TEMP_FILES =
      new TempFileCreationStrategy() {
        @Override
        public File createTempFile(String prefix, String suffix) throws IOException {
          return PrivateTempFiles.createTempFile(prefix, suffix).toFile();
        }

        @Override
        public File createTempDirectory(String prefix) throws IOException {
          return PrivateTempFiles.createTempDirectory(prefix).toFile();
        }
      };

  public ExcelExportFormat(ObjectMapper objectMapper) {
    super(objectMapper);
  }

  @Override
  public String formatType() {
    return FileTemplateFormat.EXCEL.code();
  }

  @Override
  public long generate(ExportFormatContext ctx) throws Exception {
    // POI 的 sheet XML 与模板 ZIP 同样包含业务数据；线程局部作用域覆盖生成和关闭，随后恢复原策略。
    GenerationResult result = TempFile.withStrategy(PRIVATE_TEMP_FILES, () -> {
      try {
        return new GenerationResult(generateWorkbook(ctx), null);
      } catch (Exception failure) {
        return new GenerationResult(0, failure);
      }
    });
    if (EmptyChecks.isNotNull(result.failure())) {
      throw result.failure();
    }
    return result.recordCount();
  }

  // Supplier 不声明受检异常，通过结果传回原异常，避免改变上层失败分类。
  private record GenerationResult(long recordCount, Exception failure) {}

  private long generateWorkbook(ExportFormatContext ctx) throws Exception {
    Long batchIdLong = ctx.batchId() == null ? null : Long.valueOf(String.valueOf(ctx.batchId()));
    ExportDataPlugin.DetailPage firstPage =
        ctx.dataPlugin().loadDetailPage(ctx.dataCtx(), batchIdLong, ctx.pageSize(), null);
    List<ColumnLayout> columns =
        resolveExcelColumns(ctx.dataCtx(), ctx.dataPlugin(), ctx.batch(), firstPage.rows());
    DelimitedFormatConfig formatConfig =
        resolveDelimitedFormatConfig(ctx.dataCtx().templateConfig());
    Map<String, Object> templateConfig = ctx.dataCtx().templateConfig();
    ExcelStyleOptions styleOptions = ExcelStyleOptions.from(templateConfig, this);
    int rowsPerSheet = resolveRowsPerSheet(templateConfig);
    String sheetName = resolveSheetName(templateConfig);
    int headerRows = Math.max(1, formatConfig.headerRows());

    // 文件创建只经过私有权限边界；写流不得隐式新建文件或跟随符号链接。
    if (Files.notExists(ctx.generatedFile(), LinkOption.NOFOLLOW_LINKS)) {
      OwnerOnlyFiles.createFile(ctx.generatedFile());
    } else {
      OwnerOnlyFiles.protectExisting(ctx.generatedFile(), false);
    }

    // workbook 必须纳入 try-with-resources:旧写法把 workbook 放外面 + finally close,
    // 若 Files.newOutputStream 抛异常(磁盘满 / 权限),控制流不进 try/finally → workbook
    // 已创建的 /tmp sheet-backing temp file 永不清理。Java 9+ try-with-resources 支持
    // 资源变量复用,把 workbook 与 outputStream 一起放进 try() 即可保证两者都被关闭。
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        OutputStream outputStream = Files.newOutputStream(
            ctx.generatedFile(),
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {
      ExcelSheetWriter writer = new ExcelSheetWriter(
          workbook, columns, sheetName, headerRows, rowsPerSheet, styleOptions, this);
      long recordCount = ExportPageGenerationCoordinator.generatePaged(
          ctx, firstPage, null, (batch, detail, rowIndex) -> writer.writeDataRow(batch, detail));
      workbook.write(outputStream);
      return recordCount;
    }
  }

  /**
   * 解析每 sheet 数据行数阈值。未配置 / ≤0 = 不拆(单 sheet,历史行为);配置时夹在 {@code [1, 1048575]}(.xlsx 单 sheet 1048576
   * 行硬限,留一行给表头)。
   */
  private int resolveRowsPerSheet(Map<String, Object> templateConfig) {
    if (templateConfig == null || templateConfig.isEmpty()) {
      return 0;
    }
    Integer raw = integerValue(
        firstNonNull(templateConfig.get("rows_per_sheet"), templateConfig.get("rowsPerSheet")));
    if (raw == null || raw <= 0) {
      return 0;
    }
    return Math.min(raw, ExcelSheetWriter.MAX_DATA_ROWS_PER_SHEET);
  }
}
