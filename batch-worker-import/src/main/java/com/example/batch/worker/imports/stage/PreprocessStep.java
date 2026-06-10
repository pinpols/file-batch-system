package com.example.batch.worker.imports.stage;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.config.S3StorageProperties;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.common.storage.BatchObjectStore;
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
import com.example.batch.worker.imports.stage.support.ImportStageSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

  /**
   * 对象存储拉取的单文件字节上限(防 OOM)。默认 512 MiB,系统属性 {@code batch.worker.import.max-object-bytes} 可调。超限直接
   * fail,避免把超大对象整块读进 byte[]。
   */
  private static final long MAX_OBJECT_BYTES =
      Long.getLong("batch.worker.import.max-object-bytes", 512L * 1024 * 1024);

  private final PlatformFileRuntimeRepository runtimeRepository;
  private final BatchSecurityProperties batchSecurityProperties;
  private final BatchObjectCryptoService cryptoService;
  // ADR-sim:大文件对象自动加载——内联 content 受 Kafka 消息体上限(~1MB)限制,
  // 大文件须把对象路径下发、由 worker 直接从 MinIO 拉取(payload 只带 path,不带内容)。
  private final S3StorageProperties s3StorageProperties;
  private final BatchObjectStore objectStore;

  public PreprocessStep(
      PlatformFileRuntimeRepository runtimeRepository,
      BatchSecurityProperties batchSecurityProperties,
      BatchObjectCryptoService cryptoService,
      S3StorageProperties s3StorageProperties,
      BatchObjectStore objectStore) {
    this.runtimeRepository = runtimeRepository;
    this.batchSecurityProperties = batchSecurityProperties;
    this.cryptoService = cryptoService;
    this.s3StorageProperties = s3StorageProperties;
    this.objectStore = objectStore;
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
                && !Texts.hasText(importPayload.contentBase64())
                // 大文件对象自动加载:无内联内容但带 storagePath 时,源在对象存储,放行到 resolveRawBytes 拉取。
                && !Texts.hasText(importPayload.storagePath())))) {
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

      // 大文件流式直载:无内联内容 + 带 storagePath + 纯文本无变换 → 把对象「流式」落到 spool 文件,
      // 交 PARSE 流式逐行消费,全程不把整文件读进堆(突破 byte[]/MAX_OBJECT_BYTES 内存天花板,
      // 支撑 GB 级 / 百万千万行 / 宽表长字段)。需变换(压缩/加密/preprocess_pipeline)或二进制格式时回退 byte[] 路径。
      // 仅大对象(≥ spool 阈值 16MB,本就要落盘)走流式直载;小文件继续走轻量内存 byte[] 路径
      // (设 normalizedPayload,无临时文件开销)。
      long directStreamObjectBytes =
          (importPayload != null
                  && Texts.hasText(importPayload.storagePath())
                  && !Texts.hasText(importPayload.content())
                  && !Texts.hasText(importPayload.contentBase64())
                  && canStreamObjectDirect(importPayload, templateConfig))
              ? objectSizeBytes(importPayload)
              : -1L;
      if (directStreamObjectBytes >= SPOOL_THRESHOLD_BYTES) {
        // 分片 + 安全格式(物理换行=记录边界)+ UTF-8 兼容字符集时,只 range 下载本片字节
        // (消除每片 N× 下载/解析放大);否则维持整份流式直载。range 路径任何异常都回退整份(不抛)。
        Integer partitionNo =
            intOrNull(context.getAttributes().get(PipelineRuntimeKeys.PARTITION_NO));
        Integer partitionCount =
            intOrNull(context.getAttributes().get(PipelineRuntimeKeys.PARTITION_COUNT));
        Charset directCharset = resolveCharset(importPayload, templateConfigObject);
        // 加密装饰层不支持明文 offset range 读(statSize 也是密文长度,不能做切片计算)→ 回退整份流式。
        if (objectStore.supportsRangeRead()
            && rangeSliceEligible(
                importPayload, templateConfig, partitionNo, partitionCount, directCharset)) {
          return streamObjectRangeToSpool(
              context,
              importPayload,
              templateConfig,
              templateConfigObject,
              new RangeSlice(directCharset, directStreamObjectBytes, partitionNo, partitionCount));
        }
        return streamObjectToSpoolAndReturn(
            context, importPayload, templateConfig, templateConfigObject);
      }

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
      ImportStageSupport.updateFileStatusRecoverAware(
          runtimeRepository, context, "PARSING", fileMetadata);
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
    // ADR-sim 大文件对象自动加载:无内联内容但带 storagePath → 直接从 MinIO 拉对象(扫描器登记的
    // RECEIVED 大文件 / 大数据由此入库,绕开 Kafka 消息体上限——payload 只带 path 不带内容)。
    if (importPayload != null && Texts.hasText(importPayload.storagePath())) {
      return downloadObjectBytes(importPayload);
    }
    String raw = context.getRawPayload();
    return raw == null ? new byte[0] : raw.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * 从对象存储拉取 import 源对象的原始字节。bucket 取 {@code payload.storageBucket},缺省回退默认 bucket; object 取 {@code
   * payload.storagePath}。超 {@link #MAX_OBJECT_BYTES} fail-fast 防 OOM。
   */
  private byte[] downloadObjectBytes(ImportPayload importPayload) {
    String bucket =
        Texts.hasText(importPayload.storageBucket())
            ? importPayload.storageBucket()
            : s3StorageProperties.getBucket();
    String object = importPayload.storagePath();
    try (InputStream in = objectStore.get(bucket, object)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[64 * 1024];
      long total = 0;
      int n;
      while ((n = in.read(buf)) >= 0) {
        total += n;
        if (total > MAX_OBJECT_BYTES) {
          throw new ImportPreprocessException(
              "IMPORT_PREPROCESS_OBJECT_TOO_LARGE",
              "import object exceeds max-object-bytes="
                  + MAX_OBJECT_BYTES
                  + " (bucket="
                  + bucket
                  + ", object="
                  + object
                  + "); raise batch.worker.import.max-object-bytes or split the file");
        }
        out.write(buf, 0, n);
      }
      log.info(
          "import preprocess loaded object from storage: bucket={}, object={}, bytes={}",
          bucket,
          object,
          total);
      return out.toByteArray();
    } catch (ImportPreprocessException ex) {
      throw ex;
    } catch (Exception ex) {
      // 对象缺失 / 拉取失败 → 走 PREPROCESS 优雅失败(execute 的 catch 转 ImportStageResult.failure),
      // 而非裸抛未捕获异常。
      throw new ImportPreprocessException(
          "IMPORT_PREPROCESS_OBJECT_LOAD_FAILED",
          "failed to load import object from storage (bucket="
              + bucket
              + ", object="
              + object
              + "): "
              + ex.getMessage(),
          ex);
    }
  }

  /**
   * 是否可对 storagePath 对象走「流式直载」(不读进堆):纯文本格式 + 无 compress / encrypt(非 NONE)/ preprocess_pipeline
   * 变换。需变换或二进制(EXCEL/BINARY)时返回 false,回退 byte[] 路径(受 MAX_OBJECT_BYTES 限)。
   */
  private boolean canStreamObjectDirect(ImportPayload importPayload, Map<String, Object> tc) {
    if (isBinaryImportFormat(resolveFileFormatType(importPayload, tc))) {
      return false;
    }
    Object pp = tc.get("preprocess_pipeline");
    if (pp != null
        && Texts.hasText(String.valueOf(pp))
        && !"[]".equals(String.valueOf(pp).trim())) {
      return false;
    }
    return isNoneOrBlank(tc.get("compress_type")) && isNoneOrBlank(tc.get("encrypt_type"));
  }

  /** statObject 取对象字节数;失败(对象缺失/网络)返回 -1 → 调用方不走流式,交 byte[] 路径报明确错误。 */
  private long objectSizeBytes(ImportPayload importPayload) {
    String bucket =
        Texts.hasText(importPayload.storageBucket())
            ? importPayload.storageBucket()
            : s3StorageProperties.getBucket();
    try {
      return objectStore.statSize(bucket, importPayload.storagePath());
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(PreprocessStep.class, "catch:statObject", ex);
      return -1L;
    }
  }

  private static boolean isNoneOrBlank(Object v) {
    if (v == null) {
      return true;
    }
    String s = String.valueOf(v).trim();
    return s.isEmpty() || "NONE".equalsIgnoreCase(s);
  }

  /**
   * 流式把对象存储里的源对象拷到 spool 临时文件,设 {@code IMPORT_LARGE_TEXT_PATH}/charset 交 PARSE 流式逐行消费。 全程 {@code
   * Files.copy(InputStream, Path)} 8K 缓冲流转,不分配整文件 byte[],无堆内存上限(仅受 /tmp 磁盘)。 spool 文件生命周期由 PARSE
   * 收尾删除;本方法失败时自行清理并抛 {@link ImportPreprocessException}。
   */
  private ImportStageResult streamObjectToSpoolAndReturn(
      ImportJobContext context,
      ImportPayload importPayload,
      Map<String, Object> templateConfig,
      Object templateConfigObject) {
    String bucket =
        Texts.hasText(importPayload.storageBucket())
            ? importPayload.storageBucket()
            : s3StorageProperties.getBucket();
    String object = importPayload.storagePath();
    Path spool = null;
    try {
      spool = Files.createTempFile("batch-preprocess-obj-", ".raw");
      long bytes;
      try (InputStream in = objectStore.get(bucket, object)) {
        bytes = Files.copy(in, spool, StandardCopyOption.REPLACE_EXISTING);
      }
      Charset charset = resolveCharset(importPayload, templateConfigObject);
      context.getAttributes().put(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_PATH, spool.toString());
      context.getAttributes().put(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_CHARSET, charset);
      context.setRawPayload("");
      context.getAttributes().remove("normalizedPayload");
      context.getAttributes().remove(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD);
      log.info(
          "import preprocess streamed object to spool (no heap buffering): bucket={}, object={},"
              + " bytes={}, spool={}",
          bucket,
          object,
          bytes,
          spool);
      Map<String, Object> fileMetadata = new LinkedHashMap<>();
      fileMetadata.put("preprocessed", Boolean.TRUE);
      String fmt = resolveFileFormatType(importPayload, templateConfig);
      fileMetadata.put("preprocessFormat", fmt == null ? "" : fmt);
      fileMetadata.put("sourceObject", object);
      fileMetadata.put("sourceBytes", bytes);
      ImportStageSupport.updateFileStatusRecoverAware(
          runtimeRepository, context, "PARSING", fileMetadata);
      return ImportStageResult.success(stage());
    } catch (Exception ex) {
      if (spool != null) {
        try {
          Files.deleteIfExists(spool);
        } catch (IOException ignored) {
          SwallowedExceptionLogger.warn(PreprocessStep.class, "catch:IOException", ignored);
        }
      }
      throw new ImportPreprocessException(
          "IMPORT_PREPROCESS_OBJECT_LOAD_FAILED",
          "failed to stream import object from storage (bucket="
              + bucket
              + ", object="
              + object
              + "): "
              + ex.getMessage(),
          ex);
    }
  }

  /**
   * range-slice 资格判定:多分片 + 物理换行=记录边界的安全格式 + 0x0A 安全字符集。 直载前提(纯文本/无变换/≥16MB)由调用方已 gate。任一不满足 →
   * 回退整份直载 + line-mod。
   */
  static boolean rangeSliceEligible(
      ImportPayload importPayload,
      Map<String, Object> tc,
      Integer partitionNo,
      Integer partitionCount,
      Charset charset) {
    if (partitionNo == null || partitionCount == null || partitionCount <= 1) {
      return false;
    }
    if (partitionNo < 1 || partitionNo > partitionCount) {
      return false;
    }
    if (!isNewlineSafeCharset(charset)) {
      return false;
    }
    return isRangeSliceableFormat(resolveFileFormatType(importPayload, tc), tc);
  }

  /**
   * 物理换行=记录边界才能按字节切:FIXED_WIDTH 逐行读 → 自动安全;DELIMITED/CSV/TSV 走 Univocity RFC4180 (支持引号内嵌跨行字段)→
   * 默认不安全,仅当模板 {@code partition_range_slice=true} 声明无内嵌换行才 opt-in; JSON/XML/EXCEL 等多行结构 → 不安全。
   */
  private static boolean isRangeSliceableFormat(String format, Map<String, Object> tc) {
    if (!Texts.hasText(format)) {
      return false;
    }
    String u = format.trim().toUpperCase();
    if ("FIXED_WIDTH".equals(u) || "FIXEDWIDTH".equals(u)) {
      return true;
    }
    if ("DELIMITED".equals(u) || "CSV".equals(u) || "TSV".equals(u)) {
      Object optIn = tc == null ? null : tc.get("partition_range_slice");
      return optIn != null && "true".equalsIgnoreCase(String.valueOf(optIn).trim());
    }
    return false;
  }

  /** 0x0A 始终是 LF、不会是多字节续字节的字符集(UTF-8 自同步 / ASCII / Latin-1 单字节),才能字节级扫换行切片。 */
  private static boolean isNewlineSafeCharset(Charset charset) {
    return StandardCharsets.UTF_8.equals(charset)
        || StandardCharsets.US_ASCII.equals(charset)
        || StandardCharsets.ISO_8859_1.equals(charset);
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

  /**
   * range-slice 大文件直载:对象存储 range GET(offset=rawStart)只下本片 {@code [rawStart, rawEnd)} 字节,行边界对齐后落
   * spool,置 {@link PipelineRuntimeKeys#PARTITION_PRESLICED} 让 PARSE 跳过 line-mod。 失败时清理 spool
   * 并**回退整份流式直载**(优化绝不导致导入失败)。
   */
  /** range-slice 入参打包(避免 streamObjectRangeToSpool 超 6 参,PMD ExcessiveParameterList)。 */
  private record RangeSlice(
      Charset charset, long objectBytes, int partitionNo, int partitionCount) {}

  private ImportStageResult streamObjectRangeToSpool(
      ImportJobContext context,
      ImportPayload importPayload,
      Map<String, Object> templateConfig,
      Object templateConfigObject,
      RangeSlice slice) {
    Charset charset = slice.charset();
    long objectBytes = slice.objectBytes();
    int partitionNo = slice.partitionNo();
    int partitionCount = slice.partitionCount();
    String bucket =
        Texts.hasText(importPayload.storageBucket())
            ? importPayload.storageBucket()
            : s3StorageProperties.getBucket();
    String object = importPayload.storagePath();
    long rawStart = objectBytes * (partitionNo - 1) / partitionCount;
    long rawEnd =
        partitionNo == partitionCount ? objectBytes : objectBytes * partitionNo / partitionCount;
    Path spool = null;
    try {
      spool = Files.createTempFile("batch-preprocess-obj-p" + partitionNo + "-", ".raw");
      long keptBytes;
      try (InputStream in = objectStore.getFrom(bucket, object, rawStart);
          OutputStream out =
              Files.newOutputStream(
                  spool,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.TRUNCATE_EXISTING,
                  StandardOpenOption.WRITE)) {
        keptBytes = copyPartitionRange(in, out, rawEnd - rawStart, partitionNo > 1);
      }
      context.getAttributes().put(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_PATH, spool.toString());
      context.getAttributes().put(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_CHARSET, charset);
      context.getAttributes().put(PipelineRuntimeKeys.PARTITION_PRESLICED, Boolean.TRUE);
      context.setRawPayload("");
      context.getAttributes().remove("normalizedPayload");
      context.getAttributes().remove(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD);
      log.info(
          "import preprocess range-sliced object to spool: bucket={}, object={}, partition={}/{},"
              + " offset={}, sliceBytes={}, keptBytes={}, spool={}",
          bucket,
          object,
          partitionNo,
          partitionCount,
          rawStart,
          rawEnd - rawStart,
          keptBytes,
          spool);
      Map<String, Object> fileMetadata = new LinkedHashMap<>();
      fileMetadata.put("preprocessed", Boolean.TRUE);
      String fmt = resolveFileFormatType(importPayload, templateConfig);
      fileMetadata.put("preprocessFormat", fmt == null ? "" : fmt);
      fileMetadata.put("sourceObject", object);
      fileMetadata.put("rangeSlice", partitionNo + "/" + partitionCount);
      ImportStageSupport.updateFileStatusRecoverAware(
          runtimeRepository, context, "PARSING", fileMetadata);
      return ImportStageResult.success(stage());
    } catch (Exception ex) {
      if (spool != null) {
        try {
          Files.deleteIfExists(spool);
        } catch (IOException ignored) {
          SwallowedExceptionLogger.warn(PreprocessStep.class, "catch:IOException", ignored);
        }
      }
      // range 优化失败不让导入挂:清掉 preslice 标记,回退整份流式直载(current behavior)。
      context.getAttributes().remove(PipelineRuntimeKeys.PARTITION_PRESLICED);
      log.warn(
          "import preprocess range-slice failed, fallback to full stream: object={},"
              + " partition={}/{}, err={}",
          object,
          partitionNo,
          partitionCount,
          ex.getMessage());
      return streamObjectToSpoolAndReturn(
          context, importPayload, templateConfig, templateConfigObject);
    }
  }

  /**
   * 从已定位到 rawStart 的 ranged 流拷出本片**完整行**到 out(标准 split 边界法,同 Hadoop TextInputFormat):
   *
   * <ul>
   *   <li>{@code skipPartialFirstLine}(partitionNo&gt;1)为 true:先丢弃 rawStart 后到首个 {@code '\n'}(含)的残行
   *       —— 该残行归上一分片(上一分片读过其 rawEnd 补齐了它)。
   *   <li>之后逐行拷贝:仅当**行起始偏移** {@code consumed <= sliceLen}(= rawEnd-rawStart)时才读该行,并把它读完整 (可能越过
   *       rawEnd 到行尾)。保证每条完整行被恰好一个分片拥有,无重叠无遗漏。
   * </ul>
   *
   * <p>仅在 0x0A 安全字符集下调用(UTF-8/ASCII/Latin-1),字节级扫 {@code '\n'} 不会误命中多字节续字节。 返回写出字节数。
   * package-private static 便于纯函数单测。
   */
  static long copyPartitionRange(
      InputStream rawIn, OutputStream out, long sliceLen, boolean skipPartialFirstLine)
      throws IOException {
    BufferedInputStream in =
        rawIn instanceof BufferedInputStream buffered
            ? buffered
            : new BufferedInputStream(rawIn, 64 * 1024);
    long consumed = 0; // 自 rawStart 起从流读出的字节数(含跳过的残行)
    long written = 0;
    int b;
    if (skipPartialFirstLine) {
      while ((b = in.read()) >= 0) {
        consumed++;
        if (b == '\n') {
          break;
        }
      }
    }
    // consumed 此刻 = 本分片首条完整行的起始偏移。逐行读:行起始 <= sliceLen 才属本片。
    while (consumed <= sliceLen) {
      int c = in.read();
      if (c < 0) {
        break; // EOF
      }
      consumed++;
      out.write(c);
      written++;
      if (c == '\n') {
        continue; // 空行/单字节行,循环重新判定下一行起始偏移
      }
      // 行已开读 → 读到 '\n'/EOF 整行收完(允许越过 sliceLen 补齐跨界末行)
      while ((c = in.read()) >= 0) {
        consumed++;
        out.write(c);
        written++;
        if (c == '\n') {
          break;
        }
      }
      if (c < 0) {
        break; // 末行无换行结尾
      }
    }
    return written;
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
  /**
   * P1-11 流式 buffer 大小:8KB,与 BufferedInputStream / ByteArrayOutputStream 配合避免 readAllBytes
   * 的一次性巨块分配。
   */
  private static final int DECRYPT_STREAM_BUFFER_BYTES = 8 * 1024;

  private byte[] decryptViaSpool(byte[] rawBytes) {
    Path encrypted = null;
    Path decrypted = null;
    try {
      encrypted = Files.createTempFile("batch-preprocess-enc-", ".raw");
      decrypted = Files.createTempFile("batch-preprocess-dec-", ".raw");
      Files.write(
          encrypted, rawBytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      cryptoService.decrypt(encrypted, decrypted);
      // P1-11: 用 8K BufferedInputStream + Files.copy(InputStream, OutputStream) 取代
      // Files.readAllBytes 一次性大块分配,降低 GC 抖动 + 避免 JDK 内部 grow buffer 的 2x 峰值。
      // ByteArrayOutputStream 按文件大小预分配,Files.copy 内部用 BufferedInputStream 提供的 8K buffer 流式
      // transfer。
      long size = Files.size(decrypted);
      int initialCapacity = (int) Math.min(size, Integer.MAX_VALUE - 8);
      try (InputStream in =
              new BufferedInputStream(
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
