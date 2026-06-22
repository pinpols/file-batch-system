package com.example.batch.worker.exports.stage.format;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;

/**
 * Excel 数据导出复用的 POI 样式 / 列宽 / 公式转义原语。能力与 console-api 的 {@code ConsoleExcelStyles} 同源(深蓝 #1F4E78
 * 加粗白字表头 + 公式注入转义),但<b>不放进 batch-common</b>:poi-ooxml 在 batch-common 仅 test scope,提到 compile 会把 POI
 * 拖进全部 10 个模块的运行时,得不偿失;而 worker-export 不依赖 console-api(也不应反向依赖),故就近放本模块, 与 ConsoleExcelStyles
 * 保持「行为一致、各自独立实现」。详见 PR 说明的样式抽取取舍。
 *
 * <p>SXSSF 约束:cell style 对象在一个 workbook 内有数量上限(64k),调用方务必<b>按列 / 按角色缓存复用</b>同一个 {@link
 * CellStyle},切勿每个 cell 新建。本类只负责造样式,缓存策略由调用方掌握。
 */
final class ExcelStyleSupport {

  /** 默认表头深蓝 #1F4E78,与 console 模板表头一致。 */
  private static final byte[] DEFAULT_HEADER_RGB = {0x1F, 0x4E, 0x78};

  private static final byte[] WHITE_RGB = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

  private ExcelStyleSupport() {}

  /**
   * 加粗白字 + 底色 + 细边框居中表头样式。
   *
   * @param rgb 3 字节 RGB(为 null 时退回深蓝);非 XSSF workbook 退化为 {@link IndexedColors#DARK_BLUE}
   */
  static CellStyle createHeaderStyle(Workbook workbook, byte[] rgb) {
    byte[] fill = rgb == null ? DEFAULT_HEADER_RGB : rgb;
    CellStyle style = workbook.createCellStyle();
    if (style instanceof XSSFCellStyle xssfStyle) {
      xssfStyle.setFillForegroundColor(new XSSFColor(fill, null));
    } else {
      style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
    }
    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    style.setAlignment(HorizontalAlignment.CENTER);
    style.setVerticalAlignment(VerticalAlignment.CENTER);
    style.setBorderTop(BorderStyle.THIN);
    style.setBorderBottom(BorderStyle.THIN);
    style.setBorderLeft(BorderStyle.THIN);
    style.setBorderRight(BorderStyle.THIN);
    Font font = workbook.createFont();
    font.setBold(true);
    if (font instanceof XSSFFont xssfFont) {
      xssfFont.setColor(new XSSFColor(WHITE_RGB, null));
    } else {
      font.setColor(IndexedColors.WHITE.getIndex());
    }
    style.setFont(font);
    return style;
  }

  /**
   * 按文本长度自适应列宽(单位:POI 1/256 字符宽),夹在 {@code [18, 12000]} 字符之间,与 console 的 {@code setWidths} 行为一致。
   */
  static int autoColumnWidth(String header) {
    int len = header == null ? 0 : header.length();
    return Math.min(12000, Math.max(18, len + 4) * 256);
  }

  /**
   * 防止 Excel 公式注入(CSV / Formula Injection)。以 {@code = + - @} 开头的字符串值被 Excel 当公式解析,加前缀单引号
   * 强制按文本处理。所有写 Cell 字符串的入口都应过这层。
   */
  static String escapeFormula(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    char first = value.charAt(0);
    if (first == '=' || first == '+' || first == '-' || first == '@') {
      return "'" + value;
    }
    return value;
  }
}
