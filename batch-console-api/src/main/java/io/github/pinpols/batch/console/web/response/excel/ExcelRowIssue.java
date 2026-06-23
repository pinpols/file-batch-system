package io.github.pinpols.batch.console.web.response.excel;

import java.util.List;

/** Excel 导入行级校验问题的统一响应。 */
public record ExcelRowIssue(
    String sheetName, Integer rowNo, String rowKey, String entityCode, List<String> messages) {

  /** 单 sheet 场景的便捷构造。 */
  public ExcelRowIssue(Integer rowNo, String rowKey, String entityCode, List<String> messages) {
    this(null, rowNo, rowKey, entityCode, messages);
  }
}
