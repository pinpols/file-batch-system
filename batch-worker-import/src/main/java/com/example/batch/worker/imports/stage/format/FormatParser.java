package com.example.batch.worker.imports.stage.format;

import com.example.batch.worker.imports.domain.ImportJobContext;
import java.io.BufferedWriter;

/** 将某种特定文件格式(Excel、JSON、XML 等)解析成 NDJSON 记录的策略接口。 */
public interface FormatParser {

  /**
   * 解析输入并将解析出的记录写入 writer。
   *
   * @return 解析出的记录总数
   */
  long parse(ImportJobContext context, FormatParseRequest request, BufferedWriter writer)
      throws Exception;
}
