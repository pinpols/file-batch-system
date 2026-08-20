package io.github.pinpols.batch.worker.imports.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.utils.EncodingUtils;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.PrivateTempFiles;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.imports.config.WorkerImportPayloadProperties;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import io.github.pinpols.batch.worker.imports.domain.ImportStage;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.domain.ImportWorkerType;
import io.github.pinpols.batch.worker.imports.preprocess.ImportPreprocessException;
import io.github.pinpols.batch.worker.imports.preprocess.ImportPreprocessPipeline;
import io.github.pinpols.batch.worker.imports.stage.support.ImportStageSupport;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * PREPROCESS（设计说明书 §9.3）：拉取模板、解码正文，执行 {@link ImportPreprocessPipeline} （{@code preprocess_pipeline}
 * 或隐式 {@code compress_type}/{@code encrypt_type}：UNZIP、GUNZIP、AES-GCM、摘要、RSA 验签、字符集转码），
 * 再归一化文本或保留二进制供 EXCEL 等格式在 PARSE 消费。
 *
 * <p>这里故意把“对象归属校验、变换、字符集处理和大文件落盘”放在解析前统一完成：后续 PARSE 只面对一种已经确认来源和编码的输入，
 * 不需要在每种文件格式实现里重复处理安全边界。无变换且满足换行/字符集条件时才使用 range 或流式路径；压缩、加密和不安全的字节切片会回退到完整输入，
 * 这是用可预测的正确性换取吞吐优化，避免把多字节字符或密文边界误当成记录边界。
 */
@Slf4j
@Component
@SuppressWarnings("java:S2259")
public class PreprocessStep implements ImportStageStep {

  private static final String ERROR_CODE_PREPROCESS_INVALID = "IMPORT_PREPROCESS_INVALID";

  /**
   * 解码后内存放大阈值：超过该字节数直接 spool 原始字节到临时文件，避免生成整块 UTF-16 String。默认 16 MiB， 通过 {@code
   * batch.worker.import.preprocess-spool-bytes} 调整（设 0 关闭 spool）。
   */
  private static final ObjectMapper ERROR_OBJECT_MAPPER = JsonUtils.newDefaultMapper();

  /** 对象存储拉取的单文件字节上限(防 OOM)。默认 512 MiB,由 {@code batch.worker.import.max-object-bytes} 调整。 */
  private final PlatformFileRuntimeRepository runtimeRepository;

  private final BatchSecurityProperties batchSecurityProperties;
  private final BatchObjectCryptoService cryptoService;
  private final WorkerImportPayloadProperties payloadProperties;
  private final ImportPreprocessObjectSource objectSource;

  public PreprocessStep(
      PlatformFileRuntimeRepository runtimeRepository,
      BatchSecurityProperties batchSecurityProperties,
      BatchObjectCryptoService cryptoService,
      S3StorageProperties s3StorageProperties,
      BatchObjectStore objectStore) {
    this(
        runtimeRepository,
        batchSecurityProperties,
        cryptoService,
        s3StorageProperties,
        objectStore,
        new WorkerImportPayloadProperties());
  }

  @Autowired
  public PreprocessStep(
      PlatformFileRuntimeRepository runtimeRepository,
      BatchSecurityProperties batchSecurityProperties,
      BatchObjectCryptoService cryptoService,
      S3StorageProperties s3StorageProperties,
      BatchObjectStore objectStore,
      WorkerImportPayloadProperties payloadProperties) {
    this.runtimeRepository = runtimeRepository;
    this.batchSecurityProperties = batchSecurityProperties;
    this.cryptoService = cryptoService;
    this.payloadProperties = payloadProperties;
    this.objectSource = new ImportPreprocessObjectSource(
        runtimeRepository, s3StorageProperties, objectStore, payloadProperties);
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
          ERROR_CODE_PREPROCESS_INVALID,
          "error.import.preprocess.invalid",
          new Object[] {"context is null"},
          "context is null",
          ERROR_OBJECT_MAPPER);
    }
    Map<String, Object> attrs = context.getAttributes();
    ImportPayload importPayload =
        attrs.get(PipelineRuntimeKeys.IMPORT_PAYLOAD) instanceof ImportPayload payload
            ? payload
            : null;
    if (!Texts.hasText(context.getRawPayload())
        && (importPayload == null
            || (!Texts.hasText(importPayload.content())
                && !Texts.hasText(importPayload.contentBase64())
                // 大文件对象自动加载:无内联内容但带 storagePath 时,源在对象存储,放行到 resolveRawBytes 拉取。
                && !Texts.hasText(importPayload.storagePath())))) {
      return ImportStageResult.failure(
          stage(),
          ERROR_CODE_PREPROCESS_INVALID,
          "error.import.preprocess.invalid",
          new Object[] {"raw payload is blank"},
          "raw payload is blank",
          ERROR_OBJECT_MAPPER);
    }
    try {
      if (importPayload != null && Texts.hasText(importPayload.templateCode())) {
        Map<String, Object> templateConfig = runtimeRepository.loadLatestTemplateConfig(
            context.getTenantId(), importPayload.templateCode(), ImportWorkerType.IMPORT);
        if (!templateConfig.isEmpty()) {
          attrs.put(PipelineRuntimeKeys.TEMPLATE_CONFIG, templateConfig);
        }
      }
      Object templateConfigObject = attrs.get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
      Map<String, Object> templateConfig = toStringKeyMap(templateConfigObject);

      // 越权防护:任何从 payload.storagePath 拉对象前,先核对该路径确实是本租户已登记 file_record 的
      // storage_path(逐字相等)。payload 来自上游消息,storagePath/storageBucket 字段一旦被篡改/伪造即可
      // 指向他租户对象;这里 fail-fast 把"裸信任 payload 直接拉取"收敛成"只拉本租户登记的那个对象"。
      if (importPayload != null && Texts.hasText(importPayload.storagePath())) {
        objectSource.assertObjectBelongsToTenant(context, importPayload);
      }

      // 大文件流式直载:无内联内容 + 带 storagePath + 纯文本无变换 → 把对象「流式」落到 spool 文件,
      // 交 PARSE 流式逐行消费,全程不把整文件读进堆(突破 byte[]/MAX_OBJECT_BYTES 内存天花板,
      // 支撑 GB 级 / 百万千万行 / 宽表长字段)。需变换(压缩/加密/preprocess_pipeline)或二进制格式时回退 byte[] 路径。
      // 仅大对象(≥ spool 阈值 16MB,本就要落盘)走流式直载;小文件继续走轻量内存 byte[] 路径
      // (设 normalizedPayload,无临时文件开销)。
      long directStreamObjectBytes = (importPayload != null
              && Texts.hasText(importPayload.storagePath())
              && !Texts.hasText(importPayload.content())
              && !Texts.hasText(importPayload.contentBase64())
              && ImportPreprocessObjectSource.canStreamObjectDirect(importPayload, templateConfig))
          ? objectSource.objectSizeBytes(importPayload)
          : -1L;
      if (directStreamObjectBytes >= payloadProperties.getPreprocessSpoolBytes()) {
        // 分片 + 安全格式(物理换行=记录边界)+ UTF-8 兼容字符集时,只 range 下载本片字节
        // (消除每片 N× 下载/解析放大);否则维持整份流式直载。range 路径任何异常都回退整份(不抛)。
        Integer partitionNo = intOrNull(attrs.get(PipelineRuntimeKeys.PARTITION_NO));
        Integer partitionCount = intOrNull(attrs.get(PipelineRuntimeKeys.PARTITION_COUNT));
        Charset directCharset = resolveCharset(importPayload, templateConfigObject);
        // 加密装饰层不支持明文 offset range 读(statSize 也是密文长度,不能做切片计算)→ 回退整份流式。
        if (objectSource.supportsRangeRead()
            && ImportPreprocessObjectSource.rangeSliceEligible(
                importPayload, templateConfig, partitionNo, partitionCount, directCharset)) {
          return objectSource.streamObjectRangeToSpool(
              context,
              importPayload,
              templateConfig,
              templateConfigObject,
              new ImportPreprocessObjectSource.RangeSlice(
                  directCharset, directStreamObjectBytes, partitionNo, partitionCount));
        }
        return objectSource.streamObjectToSpoolAndReturn(
            context, importPayload, templateConfig, templateConfigObject);
      }

      byte[] rawBytes = objectSource.resolveRawBytes(context, importPayload, templateConfigObject);
      // 解密由 BatchObjectCryptoService 产生的 BATCHENC 格式文件（导出存储路径）。
      // 处理 KMS 运行时闭合：在导出/入站侧经 BatchObjectCryptoService 加密的文件，
      // 在此处透明解密后再进入预处理 pipeline。
      //
      // ⚠2 (2026-05-03): 大文件走 spool 路径避免 byte[] 双倍峰值. 之前 cryptoService.decrypt(rawBytes)
      // 内部 ByteArrayInputStream → readAllBytes(), 100 MB 输入瞬间 200 MB 堆峰. 现在 > spool 阈值且加密时
      // 走 Path → Path 流式解密, 完成后释放 rawBytes 引用让 GC 回收, 堆峰降为单 100 MB.
      if (!batchSecurityProperties.isBypassMode()) {
        if (rawBytes.length > payloadProperties.getPreprocessSpoolBytes()
            && cryptoService.isEncryptedContent(rawBytes)) {
          rawBytes = decryptViaSpool(rawBytes);
        } else {
          rawBytes = cryptoService.decrypt(rawBytes);
        }
      }
      byte[] processed = ImportPreprocessPipeline.run(
          rawBytes,
          importPayload,
          templateConfig,
          batchSecurityProperties.isBypassMode(),
          payloadProperties);

      return completePreprocess(
          context, importPayload, attrs, processed, templateConfig, templateConfigObject);
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

  /**
   * 预处理管道产物收尾：文本格式字节级剥 UTF-8 BOM → 按格式分支（二进制保留字节 / 文本解码或 spool）→ 写
   * PARSING 状态与编码守卫 metadata。抽离自 {@link #execute}，控制 NCSS（PMD methodReportLevel=60）。
   */
  private ImportStageResult completePreprocess(
      ImportJobContext context,
      ImportPayload importPayload,
      Map<String, Object> attrs,
      byte[] processed,
      Map<String, Object> templateConfig,
      Object templateConfigObject) {
    String formatType =
        ImportPreprocessObjectSource.resolveFileFormatType(importPayload, templateConfig);
    // 字节级剥离 UTF-8 BOM（Windows Excel "CSV UTF-8" / 部分编辑器自动加 BOM）。只在文本格式做；
    // EXCEL/BINARY 走字节原文，BOM 属于其二进制结构的一部分，不能动。
    if (!ImportPreprocessObjectSource.isBinaryImportFormat(formatType)) {
      processed = EncodingUtils.stripUtf8Bom(processed);
    }
    if (ImportPreprocessObjectSource.isBinaryImportFormat(formatType)) {
      attrs.put(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD, processed);
      context.setRawPayload("");
      attrs.remove(PipelineRuntimeKeys.IMPORT_NORMALIZED_PAYLOAD);
    } else {
      DecodingSpec spec = resolveDecodingSpec(importPayload, templateConfigObject);
      Charset charset = spec.charset();
      if (processed.length >= payloadProperties.getPreprocessSpoolBytes()) {
        spoolLargePayload(processed, charset, context);
      } else {
        String normalized = decodeWithGuards(processed, spec, context);
        context.setRawPayload(normalized);
        attrs.put(PipelineRuntimeKeys.IMPORT_NORMALIZED_PAYLOAD, normalized);
        attrs.remove(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD);
      }
    }

    Map<String, Object> fileMetadata = new LinkedHashMap<>();
    fileMetadata.put("preprocessed", Boolean.TRUE);
    fileMetadata.put("preprocessFormat", formatType == null ? "" : formatType);
    // 编码守卫标记：D1 反向错怀疑 + B 残留 U+FFFD 计数，供前端 file_record 详情页/审计查询
    Object charsetSuspect = attrs.get("charsetSuspect");
    if (charsetSuspect != null) {
      fileMetadata.put("charsetSuspect", charsetSuspect);
    }
    Object replacementMeta = attrs.get("replacementCount");
    if (replacementMeta != null) {
      fileMetadata.put("replacementCount", replacementMeta);
    }
    // 编码探测回退命中（未配置 charset 时 UTF-8 严格解码失败 → GB18030 成功）的记录
    Object detectedCharset = attrs.get("detectedCharset");
    if (detectedCharset != null) {
      fileMetadata.put("detectedCharset", detectedCharset);
    }
    ImportStageSupport.updateFileStatusRecoverAware(
        runtimeRepository, context, "PARSING", fileMetadata);
    return ImportStageResult.success(stage());
  }

  private static Integer intOrNull(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      SwallowedExceptionLogger.info(PreprocessStep.class, "catch:NumberFormatException", ignored);
      return null;
    }
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

  /** 内存路径解码规格：charset + 是否显式配置 + 非法字符策略 + 是否开启探测回退。 */
  private record DecodingSpec(
      Charset charset,
      boolean explicitlyConfigured,
      CodingErrorAction invalidCharAction,
      boolean charsetDetectionEnabled) {}

  /**
   * 解析内存路径的解码规格。字符集仍走 {@link #resolveCharset} 的三级降级；探测回退（UTF-8 严格解码失败 → GB18030）
   * 是<b>保守默认</b>：仅当模板显式 {@code charset_detect=true} 且「未显式配置 charset」时于 {@link #decodeWithGuards}
   * 内触发；默认保持 fail-fast（GB18030 是超集、接受大量字节序列，盲目回退会把非 GB 系编码静默解成乱码入库）。
   */
  private DecodingSpec resolveDecodingSpec(
      ImportPayload importPayload, Object templateConfigObject) {
    Charset charset = resolveCharset(importPayload, templateConfigObject);
    boolean explicit = hasExplicitCharset(importPayload, templateConfigObject);
    return new DecodingSpec(
        charset,
        explicit,
        resolveInvalidCharAction(templateConfigObject),
        charsetDetectionEnabled(templateConfigObject));
  }

  /** 探测回退开关：模板 {@code charset_detect=true} 才允许自动尝试 GB18030。 */
  private static boolean charsetDetectionEnabled(Object templateConfigObject) {
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object raw = templateConfig.get("charset_detect");
      return raw != null && "true".equalsIgnoreCase(String.valueOf(raw).trim());
    }
    return false;
  }

  private static boolean hasExplicitCharset(
      ImportPayload importPayload, Object templateConfigObject) {
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object targetCharset = templateConfig.get("target_charset");
      if (targetCharset != null && Texts.hasText(String.valueOf(targetCharset))) {
        return true;
      }
      Object charset = templateConfig.get("charset");
      if (charset != null && Texts.hasText(String.valueOf(charset))) {
        return true;
      }
    }
    return importPayload != null
        && (Texts.hasText(importPayload.targetCharset()) || Texts.hasText(importPayload.charset()));
  }

  /**
   * 非法字符策略：模板 {@code invalid_char_policy} = {@code REPLACE} 时用 U+FFFD 替换（配合
   * replacementCount 观测），默认 {@code FAIL}（严格 REPORT，解码失败直接 PREPROCESS_FAILED）。
   */
  private static CodingErrorAction resolveInvalidCharAction(Object templateConfigObject) {
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object policy = templateConfig.get("invalid_char_policy");
      if (policy != null && Texts.hasText(String.valueOf(policy))) {
        String raw = String.valueOf(policy).trim();
        if ("REPLACE".equalsIgnoreCase(raw)) {
          return CodingErrorAction.REPLACE;
        }
        if ("FAIL".equalsIgnoreCase(raw)) {
          return CodingErrorAction.REPORT;
        }
        throw new ImportPreprocessException(
            "IMPORT_PREPROCESS_INVALID_POLICY", "unsupported invalid_char_policy: " + raw);
      }
    }
    return CodingErrorAction.REPORT;
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
   *   <li>D2 — 编码探测回退（保守默认，opt-in）：模板 {@code charset_detect=true} 且未显式配置 charset、UTF-8 严格解码
   *       失败、字节含非 ASCII 时，尝试 GB18030（GBK 超集）；命中则 context 写入 {@code detectedCharset}，解码继续。
   *       默认关闭——GB18030 解码接受度远高于 UTF-8，自动回退可能把 Shift-JIS/Latin-1 等静默解成乱码，宁可失败让人工配 charset。
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
  /**
   * P1-11 流式 buffer 大小:8KB,与 BufferedInputStream / ByteArrayOutputStream 配合避免 readAllBytes
   * 的一次性巨块分配。
   */
  private static final int DECRYPT_STREAM_BUFFER_BYTES = 8 * 1024;

  private byte[] decryptViaSpool(byte[] rawBytes) {
    Path encrypted = null;
    Path decrypted = null;
    try {
      encrypted = PrivateTempFiles.createTempFile("batch-preprocess-enc-", ".raw");
      decrypted = PrivateTempFiles.createTempFile("batch-preprocess-dec-", ".raw");
      Files.write(
          encrypted, rawBytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      cryptoService.decrypt(encrypted, decrypted);
      // P1-11: 用 8K BufferedInputStream + Files.copy(InputStream, OutputStream) 取代
      // Files.readAllBytes 一次性大块分配,降低 GC 抖动 + 避免 JDK 内部 grow buffer 的 2x 峰值。
      // ByteArrayOutputStream 按文件大小预分配,Files.copy 内部用 BufferedInputStream 提供的 8K buffer 流式
      // transfer。
      long size = Files.size(decrypted);
      long maxByteArrayCapacity = Integer.MAX_VALUE - 8L;
      int initialCapacity = (int) Math.min(size, maxByteArrayCapacity);
      try (InputStream in = new BufferedInputStream(
              Files.newInputStream(decrypted), DECRYPT_STREAM_BUFFER_BYTES);
          ByteArrayOutputStream out = new ByteArrayOutputStream(initialCapacity)) {
        in.transferTo(out);
        return out.toByteArray();
      }
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
      spool = PrivateTempFiles.createTempFile("batch-preprocess-", ".raw");
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
    context.getAttributes().remove(PipelineRuntimeKeys.IMPORT_NORMALIZED_PAYLOAD);
    context.getAttributes().remove(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD);
    log.info(
        "[ImportPreprocess] large payload {} bytes spooled to {}; PARSE will stream decode as {}",
        processed.length,
        spool,
        charset);
  }

  private String decodeWithGuards(byte[] processed, DecodingSpec spec, ImportJobContext context) {
    Charset charset = spec.charset();
    String decoded;
    try {
      decoded = decodeStrict(processed, charset, spec.invalidCharAction());
    } catch (ImportPreprocessException ex) {
      // D2：仅「显式开启探测 + 未显式配置」时回退；显式配置解码失败仍 fail-fast（设计：配置即契约）。
      if (spec.charsetDetectionEnabled()
          && !spec.explicitlyConfigured()
          && StandardCharsets.UTF_8.equals(charset)
          && hasNonAscii(processed)) {
        try {
          decoded = decodeStrict(processed, EncodingUtils.GB18030, CodingErrorAction.REPORT);
          charset = EncodingUtils.GB18030;
          log.warn(
              "[ImportPreprocess] UTF-8 strict decode failed and no explicit charset configured;"
                  + " detected GB18030 (GBK superset). See file_record.metadata.detectedCharset");
          context.getAttributes().put("detectedCharset", charset.name());
        } catch (ImportPreprocessException ignored) {
          SwallowedExceptionLogger.info(
              PreprocessStep.class, "catch:ImportPreprocessException", ignored);
          throw ex;
        }
      } else {
        throw ex;
      }
    }
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

  private static String decodeStrict(byte[] bytes, Charset charset, CodingErrorAction action) {
    CharsetDecoder decoder =
        charset.newDecoder().onMalformedInput(action).onUnmappableCharacter(action);
    try {
      return decoder
          .decode(ByteBuffer.wrap(bytes == null ? new byte[0] : bytes))
          .toString();
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
