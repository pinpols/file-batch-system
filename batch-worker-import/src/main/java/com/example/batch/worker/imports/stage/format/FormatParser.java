package com.example.batch.worker.imports.stage.format;

import com.example.batch.worker.imports.domain.ImportJobContext;
import java.io.BufferedWriter;

/**
 * Strategy interface for parsing a specific file format (Excel, JSON, XML, etc.) into NDJSON
 * records.
 */
public interface FormatParser {

  /**
   * Parse the input and write parsed records to the writer.
   *
   * @return the total number of records parsed
   */
  long parse(ImportJobContext context, FormatParseRequest request, BufferedWriter writer)
      throws Exception;
}
