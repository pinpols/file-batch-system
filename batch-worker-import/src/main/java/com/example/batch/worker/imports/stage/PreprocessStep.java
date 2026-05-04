package com.example.batch.worker.imports.stage;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.common.utils.EncodingUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.domain.ImportWorkerType;
import com.example.batch.worker.imports.preprocess.ImportPreprocessException;
import com.example.batch.worker.imports.preprocess.ImportPreprocessPipeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PREPROCESS（设计说明书 §9.3）：拉取模板、解码正文，执行 {@link ImportPreprocessPipeline} （{@code preprocess_pipeline}
 * 或隐式 {@code compress_type}/{@code encrypt_type}：UNZIP、GUNZIP、AES-GCM、摘要、RSA 验签、字符集转码），
 * 再归一化文本或保留二进制供 EXCEL 等格式在 PARSE 消费。
 */
@Slf4j
@Component
public class PreprocessStep implements ImportStageStep {

  /**
   * 解码后内存放大阈值：超过该字节数直接 spool 原始字节到临时文件，避免生成整块 UTF-16 String。默认 16 MiB，可通过系统属性 {@code
   * batch.worker.import.preprocess-spool-bytes} 调整（设 0 关闭 spool，全部走 byte[] → String 路径）。
   */
  private static final int SPOOL_THRESHOLD_BYTES =
      Integer.getInteger("batch.worker.import.preprocess-spool-bytes", 16 * 1024 * 1024);

  private static final ObjectMapper ERROR_OBJECT_MAPPER = new ObjectMapper();

  private final PlatformFileRuntimeRepository runtimeRepository;
  private final BatchSecurityProperties batchSecurityProperties;
  private final BatchObjectCryptoService cryptoService;

  public PreprocessStep(
      PlatformFileRuntimeRepository runtimeRepository,
      BatchSecurityProperties batchSecurityProperties,
      BatchObjectCryptoService cryptoService) {
    this.runtimeRepository = runtimeRepository;
    this.batchSecurityProperties = batchSecurityProperties;
    this.cryptoService = cryptoService;
  }

  @Override
  public ImportStage stage() {
    return ImportStage.PREPROCESS;
  }

  @Override
  public ImportStageResult execute(ImportJobContext context) {
    if (context == null) {
      return ImportStageResult.failure(
          stage(),
          "IMPORT_PREPROCESS_INVALID",
          "error.import.preprocess.invalid",
          new Object[] {"context is null"},
          "context is null",
          ERROR_OBJECT_MAPPER);
    }
    ImportPayload importPayload =
        context.getAttributes().get("importPayload") instanceof ImportPayload payload
            ? payload
            : null;
    if (!Texts.hasText(context.getRawPayload())
        && (importPayload == null
            || (!Texts.hasText(importPayload.content())
                && !Texts.hasText(importPayload.contentBase64())))) {
      return ImportStageResult.failure(
          stage(),
          "IMPORT_PREPROCESS_INVALID",
          "error.import.preprocess.invalid",
          new Object[] {"raw payload is blank"},
          "raw payload is blank",
          ERROR_OBJECT_MAPPER);
    }
    try {
      if (importPayload != null && Texts.hasText(importPayload.templateCode())) {
        Map<String, Object> templateConfig =
            runtimeRepository.loadLatestTemplateConfig(
                context.getTenantId(), importPayload.templateCode(), ImportWorkerType.IMPORT);
        if (!templateConfig.isEmpty()) {
          context.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, templateConfig);
        }
      }
      Object templateConfigObject =
          context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
      Map<String, Object> templateConfig = toStringKeyMap(templateConfigObject);

      byte[] rawBytes = resolveRawBytes(context, importPayload, templateConfigObject);
      // 解密由 BatchObjectCryptoService 产生的 BATCHENC 格式文件（导出存储路径）。
      // 处理 KMS 运行时闭合：在导出/入站侧经 BatchObjectCryptoService 加密的文件，
      // 在此处透明解密后再进入预处理 pipeline。
      //
      // ⚠2 (2026-05-03): 大文件走 spool 路径避免 byte[] 双倍峰值. 之前 cryptoService.decrypt(rawBytes)
      // 内部 ByteArrayInputStream → readAllBytes(), 100 MB 输入瞬间 200 MB 堆峰. 现在 > spool 阈值且加密时
      // 走 Path → Path 流式解密, 完成后释放 rawBytes 引用让 GC 回收, 堆峰降为单 100 MB.
      if (!batchSecurityProperties.isBypassMode()) {
        if (rawBytes.length > SPOOL_THRESHOLD_BYTES && cryptoService.isEncryptedContent(rawBytes)) {
          rawBytes = decryptViaSpool(rawBytes);
        } else {
          rawBytes = cryptoService.decrypt(rawBytes);
        }
      }
      byte[] processed =
          ImportPreprocessPipeline.run(
              rawBytes, importPayload, templateConfig, batchSecurityProperties.isBypassMode());

      String formatType = resolveFileFormatType(importPayload, templateConfig);
      if (isBinaryImportFormat(formatType)) {
        context.getAttributes().put(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD, processed);
        context.setRawPayload("");
        context.getAttributes().remove("normalizedPayload");
      } else {
        Charset charset = resolveCharset(importPayload, templateConfigObject);
        if (processed.length >= SPOOL_THRESHOLD_BYTES) {
          spoolLargePayload(processed, charset, context);
        } else {
          String normalized = decodeWithGuards(processed, charset, context);
          context.setRawPayload(normalized);
          context.getAttributes().put("normalizedPayload", normalized);
          context.getAttributes().remove(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD);
        }
      }

      Map<String, Object> fileMetadata = new LinkedHashMap<>();
      fileMetadata.put("preprocessed", Boolean.TRUE);
      fileMetadata.put("preprocessFormat", formatType == null ? "" : formatType);
      // 编码守卫标记：D1 反向错怀疑 + B 残留 U+FFFD 计数，供前端 file_record 详情页/审计查询
      Object charsetSuspect = context.getAttributes().get("charsetSuspect");
      if (charsetSuspect != null) {
        fileMetadata.put("charsetSuspect", charsetSuspect);
      }
      Object replacementMeta = context.getAttributes().get("replacementCount");
      if (replacementMeta != null) {
        fileMetadata.put("replacementCount", replacementMeta);
      }
      runtimeRepository.updateFileStatus(
          runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
          "PARSING",
          fileMetadata);
      return ImportStageResult.success(stage());
    } catch (ImportPreprocessException ex) {
      SwallowedExceptionLogger.info(PreprocessStep.class, "catch:ImportPreprocessException", ex);

      return ImportStageResult.failure(
          stage(),
          ex.errorCode(),
          "error.import.preprocess.failed",
          new Object[] {ex.getMessage()},
          ex.getMessage(),
          ERROR_OBJECT_MAPPER);
    }
  }

  private static boolean isBinaryImportFormat(String formatType) {
    if (!Texts.hasText(formatType)) {
      return false;
    }
    String u = formatType.trim().toUpperCase();
    return "EXCEL".equals(u) || "BINARY".equals(u);
  }

  private static String resolveFileFormatType(
      ImportPayload importPayload, Map<String, Object> templateConfig) {
    if (importPayload != null && Texts.hasText(importPayload.fileFormatType())) {
      return importPayload.fileFormatType();
    }
    Object v = templateConfig.get("file_format_type");
    if (v != null && Texts.hasText(String.valueOf(v))) {
      return String.valueOf(v);
    }
    return null;
  }

  private byte[] resolveRawBytes(
      ImportJobContext context, ImportPayload importPayload, Object templateConfigObject) {
    if (importPayload != null && Texts.hasText(importPayload.contentBase64())) {
      return Base64.getDecoder().decode(importPayload.contentBase64().trim());
    }
    if (importPayload != null && Texts.hasText(importPayload.content())) {
      Charset cs = resolveCharsetForContentBytes(importPayload, templateConfigObject);
      return importPayload.content().getBytes(cs);
    }
    String raw = context.getRawPayload();
    return raw == null ? new byte[0] : raw.getBytes(StandardCharsets.UTF_8);
  }

  private Charset resolveCharsetForContentBytes(
      ImportPayload importPayload, Object templateConfigObject) {
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object charset = templateConfig.get("charset");
      if (charset != null && Texts.hasText(String.valueOf(charset))) {
        return EncodingUtils.resolve(String.valueOf(charset));
      }
    }
    if (importPayload != null && Texts.hasText(importPayload.charset())) {
      return EncodingUtils.resolve(importPayload.charset());
    }
    return StandardCharsets.UTF_8;
  }

  private static Map<String, Object> toStringKeyMap(Object templateConfigObject) {
    if (!(templateConfigObject instanceof Map<?, ?> raw)) {
      return Map.of();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    raw.forEach((k, v) -> out.put(String.valueOf(k), v));
    return out;
  }

  private String normalizeText(String source) {
    if (source == null) {
      return "";
    }
    String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
    if (!normalized.isEmpty() && normalized.charAt(0) == '\uFEFF') {
      normalized = normalized.substring(1);
    }
    return normalized.trim();
  }

  private Charset resolveCharset(ImportPayload importPayload, Object templateConfigObject) {
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object targetCharset = templateConfig.get("target_charset");
      if (targetCharset != null && Texts.hasText(String.valueOf(targetCharset))) {
        return EncodingUtils.resolve(String.valueOf(targetCharset));
      }
      Object charset = templateConfig.get("charset");
      if (charset != null && Texts.hasText(String.valueOf(charset))) {
        return EncodingUtils.resolve(String.valueOf(charset));
      }
    }
    if (importPayload != null && Texts.hasText(importPayload.targetCharset())) {
      return EncodingUtils.resolve(importPayload.targetCharset());
    }
    if (importPayload != null && Texts.hasText(importPayload.charset())) {
      return EncodingUtils.resolve(importPayload.charset());
    }
    return StandardCharsets.UTF_8;
  }

  /**
   * 文本解码三层守卫：把原始字节变成已归一化的 UTF-16 字符串，同时把可疑信号写入 context。
   *
   * <ul>
   *   <li>A — 严格解码：非法字节 / 非 mappable 字符抛 {@code IMPORT_PREPROCESS_DECODE_FAILED}, 避免默认 REPLACE 让
   *       U+FFFD 静默入库
   *   <li>D1 — 反向错检测：声明非 UTF-8 但字节同时通过 UTF-8 严格解码 → context 写入 {@code charsetSuspect=LIKELY_UTF8},
   *       让下游把标记写进 {@code file_record.metadata}
   *   <li>B — 残留 U+FFFD 扫描：解码结果仍含 U+FFFD (源文件自带 / charset 内置替换) → context 写入 {@code
   *       replacementCount}
   * </ul>
   */
  /**
   * 大文件 spool：把原始字节写临时文件，PARSE 阶段用 InputStreamReader 按 charset 流式按行解码， 避免 byte[] → UTF-16 String 的
   * 1.5-2x 内存放大。A 严格解码在 PARSE 阶段读时隐式触发（charset decoder REPORT 行为）；B/D1 为观测层，大文件场景下跳过以换取内存。IO 错误转为
   * {@link ImportPreprocessException} 以对齐主链路异常语义。
   */
  /**
   * ⚠2 (2026-05-03): 大文件加密内容走 Path → Path 流式解密. 之前 cryptoService.decrypt(rawBytes) 内部
   * readAllBytes() 把结果再分配一次 byte[], 100 MB 输入瞬间 200 MB 堆峰. 现在写 temp file 后立刻让 caller GC rawBytes,
   * 解密结果只占单次 byte[] 大小. 失败按原 IOException 透传成 IllegalStateException, 保持调用方语义.
   */
  private byte[] decryptViaSpool(byte[] rawBytes) {
    Path encrypted = null;
    Path decrypted = null;
    try {
      encrypted = Files.createTempFile("batch-preprocess-enc-", ".raw");
      decrypted = Files.createTempFile("batch-preprocess-dec-", ".raw");
      Files.write(
          encrypted, rawBytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      cryptoService.decrypt(encrypted, decrypted);
      return Files.readAllBytes(decrypted);
    } catch (IOException ex) {
      throw new IllegalStateException("failed to spool-decrypt large payload", ex);
    } finally {
      if (encrypted != null) {
        try {
          Files.deleteIfExists(encrypted);
        } catch (IOException ignored) {
          SwallowedExceptionLogger.warn(PreprocessStep.class, "catch:IOException", ignored);

          // 临时文件清理失败不阻断主路径; OS 会按 /tmp 策略最终回收
        }
      }
      if (decrypted != null) {
        try {
          Files.deleteIfExists(decrypted);
        } catch (IOException ignored) {
          SwallowedExceptionLogger.warn(PreprocessStep.class, "catch:IOException", ignored);

          // 同上
        }
      }
    }
  }

  private void spoolLargePayload(byte[] processed, Charset charset, ImportJobContext context) {
    Path spool;
    try {
      spool = Files.createTempFile("batch-preprocess-", ".raw");
      Files.write(spool, processed, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    } catch (IOException ex) {
      throw new ImportPreprocessException(
          "IMPORT_PREPROCESS_SPOOL_FAILED",
          "failed to spool large payload to temp file: " + ex.getMessage(),
          ex);
    }
    context.getAttributes().put(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_PATH, spool.toString());
    context.getAttributes().put(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_CHARSET, charset);
    context.setRawPayload("");
    context.getAttributes().remove("normalizedPayload");
    context.getAttributes().remove(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD);
    log.info(
        "[ImportPreprocess] large payload {} bytes spooled to {}; PARSE will stream decode as {}",
        processed.length,
        spool,
        charset);
  }

  private String decodeWithGuards(byte[] processed, Charset charset, ImportJobContext context) {
    String decoded = decodeStrict(processed, charset);
    if (!StandardCharsets.UTF_8.equals(charset)
        && hasNonAscii(processed)
        && looksLikeUtf8(processed)) {
      log.warn(
          "[ImportPreprocess] declared charset={} but bytes also pass UTF-8 strict decode;"
              + " source may actually be UTF-8. See file_record.metadata.charsetSuspect",
          charset);
      context.getAttributes().put("charsetSuspect", "LIKELY_UTF8");
    }
    long replacementCount = countReplacement(decoded);
    if (replacementCount > 0) {
      log.warn(
          "[ImportPreprocess] decoded payload contains {} U+FFFD after decoding with {};"
              + " declared charset likely inaccurate, see file_record.metadata",
          replacementCount,
          charset);
      context.getAttributes().put("replacementCount", replacementCount);
    }
    return normalizeText(decoded);
  }

  private static String decodeStrict(byte[] bytes, Charset charset) {
    CharsetDecoder decoder =
        charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return decoder.decode(ByteBuffer.wrap(bytes == null ? new byte[0] : bytes)).toString();
    } catch (CharacterCodingException ex) {
      throw new ImportPreprocessException(
          "IMPORT_PREPROCESS_DECODE_FAILED",
          "failed to decode bytes as " + charset.name() + ": " + ex.getMessage(),
          ex);
    }
  }

  private static long countReplacement(String text) {
    if (text == null || text.isEmpty()) {
      return 0L;
    }
    return text.chars().filter(c -> c == '\uFFFD').count();
  }

  private static boolean looksLikeUtf8(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return false;
    }
    try {
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes));
      return true;
    } catch (CharacterCodingException e) {
      SwallowedExceptionLogger.info(PreprocessStep.class, "catch:CharacterCodingException", e);

      return false;
    }
  }

  private static boolean hasNonAscii(byte[] bytes) {
    if (bytes == null) {
      return false;
    }
    for (byte b : bytes) {
      if ((b & 0x80) != 0) {
        return true;
      }
    }
    return false;
  }
}
