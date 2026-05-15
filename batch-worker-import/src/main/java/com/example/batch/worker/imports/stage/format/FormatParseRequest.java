package com.example.batch.worker.imports.stage.format;

import com.example.batch.worker.imports.domain.ImportPayload;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Immutable parameter object passed to each {@link FormatParser}.
 *
 * <p><b>大文件 spool</b>：当 PREPROCESS 检测到 payload 超过堆安全阈值时不 decode 成 {@code String}（UTF-16 放大
 * 1.5-2x），而是把原始字节 spool 到 {@code spoolPath}，让 PARSE 通过 {@link #openTextReader()} 用 {@code
 * InputStreamReader(FileInputStream, charset)} 流式按 行解码消费。{@code spoolPath == null} 时回落 {@code
 * payloadText} 走 {@link StringReader}。
 */
public record FormatParseRequest(
    String payloadText,
    byte[] binaryPayload,
    ImportPayload importPayload,
    Object templateConfig,
    boolean preserveLogicalRow,
    Path spoolPath,
    Charset spoolCharset) {

  /** 5-参数便捷构造：仍走 {@code payloadText}（小文件路径），spool 相关字段为空。 */
  public FormatParseRequest(
      String payloadText,
      byte[] binaryPayload,
      ImportPayload importPayload,
      Object templateConfig,
      boolean preserveLogicalRow) {
    this(payloadText, binaryPayload, importPayload, templateConfig, preserveLogicalRow, null, null);
  }

  /**
   * 打开文本 Reader。优先走 {@code spoolPath}（大文件流式）；否则回落 {@code payloadText}（小文件 整块）。
   *
   * <p>spool 路径下 {@link CharsetDecoder} 显式设 {@link CodingErrorAction#REPORT}——和 PreprocessStep 的 A
   * 严格解码等价，非法字节 → {@code MalformedInputException} 沿 {@code reader.readLine()} 抛出， 避免 JDK 默认 {@code
   * REPLACE} 行为把 U+FFFD 静默写进下游解析结果。调用方 try-with-resources 关闭。
   */
  public BufferedReader openTextReader() throws IOException {
    if (spoolPath != null) {
      Charset cs = spoolCharset != null ? spoolCharset : StandardCharsets.UTF_8;
      CharsetDecoder decoder =
          cs.newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      // S1-6 / R2-P2-7：InputStreamReader ctor 罕见情况下抛异常时，原始 InputStream 未关闭 → fd 泄漏。
      // 包一层 try-catch 在中间环节失败时显式关 in。正常路径下 BufferedReader.close 会向下关闭。
      InputStream in = Files.newInputStream(spoolPath);
      try {
        return new BufferedReader(new InputStreamReader(in, decoder));
      } catch (RuntimeException | Error ex) {
        try {
          in.close();
        } catch (IOException closeEx) {
          ex.addSuppressed(closeEx);
        }
        throw ex;
      }
    }
    return new BufferedReader(new StringReader(payloadText == null ? "" : payloadText));
  }

  /** 是否有可消费的文本内容（spool 文件或非空 payloadText）。 */
  public boolean hasText() {
    return spoolPath != null || (payloadText != null && !payloadText.isEmpty());
  }
}
