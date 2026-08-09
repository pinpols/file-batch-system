package io.github.pinpols.batch.worker.exports.stage.format;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * ADR-038 P3 Export GENERATE 续跑:基于 {@link FileOutputStream} 的 UTF-8 文本写入句柄,提供「页边界 fsync + 取字节大小」
 * 能力(普通 {@code Files.newBufferedWriter} 拿不到 {@link java.io.FileDescriptor} 无法 fsync)。
 *
 * <p>两种打开模式:
 *
 * <ul>
 *   <li>{@link #truncate(Path)} —— 截断到 0 后写(首跑;等价旧的 {@code TRUNCATE_EXISTING})。
 *   <li>{@link #append(Path)} —— 在文件末尾追加(续跑;调用方须先用 FileChannel.truncate 将残尾截断到 fsync 过的字节位点)。
 * </ul>
 *
 * <p>{@link #flushAndSync()} 在每个分页边界调:flush BufferedWriter → {@code FileDescriptor.sync()}(数据落盘)→
 * 返回当前文件字节数,这个字节数即下次续跑的 truncate 目标。{@link #close()} 收尾再 fsync 一次保证完整文件 durable。
 */
final class ResumableExportFile implements Closeable {

  private final FileOutputStream fos;
  private final BufferedWriter writer;

  private ResumableExportFile(Path path, boolean append, Charset charset, boolean withBom)
      throws IOException {
    this.fos = new FileOutputStream(path.toFile(), append);
    // 严格编码器：目标字符集无法表达的字符（GBK 遇到 emoji / 生僻字）必须抛 UnmappableCharacterException，
    // 由 format 层转成带行号/字段的明确错误。OutputStreamWriter 默认 REPLACE 会把不可映射字符静默替换成 '?'，
    // 等于脏数据落盘且无法追溯。
    CharsetEncoder encoder = charset
        .newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    this.writer = new BufferedWriter(new OutputStreamWriter(fos, encoder));
    if (withBom && !append) {
      writeBom(charset);
    }
  }

  /**
   * 按目标字符集写 BOM 前缀（仅首跑 truncate 时；续跑 append 时 BOM 已在残文件开头，不能重写）。
   *
   * <p>GBK / GB18030 / ISO-8859-1 没有 BOM 约定，{@code with_bom=true} 对这些字符集无意义，静默忽略。
   */
  private void writeBom(Charset charset) throws IOException {
    if (StandardCharsets.UTF_8.equals(charset)) {
      fos.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
    } else if (StandardCharsets.UTF_16.equals(charset)) {
      fos.write(new byte[] {(byte) 0xFE, (byte) 0xFF});
    } else if (StandardCharsets.UTF_16LE.equals(charset)) {
      fos.write(new byte[] {(byte) 0xFF, (byte) 0xFE});
    }
  }

  /** 截断到 0 后写(首跑)。 */
  static ResumableExportFile truncate(Path path) throws IOException {
    return truncate(path, StandardCharsets.UTF_8, false);
  }

  /** 截断到 0 后写(首跑)，指定目标字符集与 BOM 策略。 */
  static ResumableExportFile truncate(Path path, Charset charset, boolean withBom)
      throws IOException {
    return new ResumableExportFile(path, false, charset, withBom);
  }

  /** 末尾追加(续跑);调用方须保证文件已被 truncate 到 fsync 过的字节位点。 */
  static ResumableExportFile append(Path path) throws IOException {
    return append(path, StandardCharsets.UTF_8);
  }

  /** 末尾追加(续跑)，沿用首跑时的目标字符集（BOM 已在文件开头，不重写）。 */
  static ResumableExportFile append(Path path, Charset charset) throws IOException {
    return new ResumableExportFile(path, true, charset, false);
  }

  BufferedWriter writer() {
    return writer;
  }

  /** flush + fsync,返回当前文件字节数(= 下次续跑的 truncate 目标偏移)。 */
  long flushAndSync() throws IOException {
    writer.flush();
    fos.getFD().sync();
    return fos.getChannel().size();
  }

  @Override
  public void close() throws IOException {
    try {
      writer.flush();
      fos.getFD().sync();
    } finally {
      writer.close();
    }
  }
}
