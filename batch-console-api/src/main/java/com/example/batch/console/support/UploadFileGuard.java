package com.example.batch.console.support;

import com.example.batch.common.exception.BizException;
import com.example.batch.common.enums.ResultCode;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传文件白名单 + 魔数（magic bytes）校验。
 *
 * <p>仅依赖 {@code Content-Type} / 扩展名会被客户端伪造，配合首 N 字节的文件签名判断
 * 才能真正识别文件类型。常见签名：
 *
 * <ul>
 *   <li>XLSX / DOCX / PPTX / ZIP: {@code 50 4B 03 04}（PK\x03\x04）或 {@code 50 4B 05 06}（空 ZIP）
 *   <li>XLS（旧 Excel / CFB）: {@code D0 CF 11 E0 A1 B1 1A E1}
 *   <li>PDF: {@code 25 50 44 46}（%PDF）
 *   <li>CSV：纯文本，无魔数；按扩展名 + 文本内容特征判断
 * </ul>
 */
public final class UploadFileGuard {

  private static final byte[] ZIP_LOCAL_HEADER = {0x50, 0x4B, 0x03, 0x04};
  private static final byte[] ZIP_EMPTY_HEADER = {0x50, 0x4B, 0x05, 0x06};
  private static final byte[] CFB_HEADER = {
    (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
    (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
  };
  private static final byte[] PDF_HEADER = {0x25, 0x50, 0x44, 0x46};

  private static final Set<String> EXCEL_EXTENSIONS = Set.of("xlsx", "xls");
  private static final Set<String> CSV_EXTENSIONS = Set.of("csv", "txt");

  private UploadFileGuard() {}

  /** 校验上传为合法 Excel（xlsx / xls），文件名和魔数必须同时通过。 */
  public static void requireExcel(MultipartFile file) {
    requireNonEmpty(file);
    requireExtension(file, EXCEL_EXTENSIONS);
    byte[] head = peek(file, 8);
    boolean isXlsx = startsWith(head, ZIP_LOCAL_HEADER) || startsWith(head, ZIP_EMPTY_HEADER);
    boolean isXls = startsWith(head, CFB_HEADER);
    if (!isXlsx && !isXls) {
      reject(file, "file content is not a valid Excel workbook (no xlsx/xls signature)");
    }
  }

  /** CSV 允许纯文本，仅检查扩展名 + 非空。 */
  public static void requireCsv(MultipartFile file) {
    requireNonEmpty(file);
    requireExtension(file, CSV_EXTENSIONS);
  }

  /** PDF 校验。 */
  public static void requirePdf(MultipartFile file) {
    requireNonEmpty(file);
    requireExtension(file, Set.of("pdf"));
    if (!startsWith(peek(file, 4), PDF_HEADER)) {
      reject(file, "file content is not a valid PDF (no %PDF signature)");
    }
  }

  private static void requireNonEmpty(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "uploaded file is empty");
    }
  }

  private static void requireExtension(MultipartFile file, Set<String> allowed) {
    String name = file.getOriginalFilename();
    if (name == null || name.isBlank()) {
      reject(file, "uploaded file missing original filename");
    }
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) {
      reject(file, "uploaded file missing extension");
    }
    String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
    if (!allowed.contains(ext)) {
      reject(file, "file extension ." + ext + " not allowed (want: " + allowed + ")");
    }
  }

  private static byte[] peek(MultipartFile file, int n) {
    byte[] head = new byte[n];
    try (InputStream in = file.getInputStream()) {
      int read = 0;
      while (read < n) {
        int r = in.read(head, read, n - read);
        if (r < 0) {
          break;
        }
        read += r;
      }
      if (read < n) {
        reject(file, "file too short to determine type (< " + n + " bytes)");
      }
    } catch (IOException ex) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "failed to read upload: " + ex.getMessage());
    }
    return head;
  }

  private static boolean startsWith(byte[] buf, byte[] prefix) {
    if (buf.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (buf[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private static void reject(MultipartFile file, String reason) {
    throw new BizException(
        ResultCode.INVALID_ARGUMENT,
        "file rejected: name=" + file.getOriginalFilename() + ", reason=" + reason);
  }
}
