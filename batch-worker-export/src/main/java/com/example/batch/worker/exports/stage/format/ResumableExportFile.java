package com.example.batch.worker.exports.stage.format;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
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

  private ResumableExportFile(Path path, boolean append) throws IOException {
    this.fos = new FileOutputStream(path.toFile(), append);
    this.writer = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));
  }

  /** 截断到 0 后写(首跑)。 */
  static ResumableExportFile truncate(Path path) throws IOException {
    return new ResumableExportFile(path, false);
  }

  /** 末尾追加(续跑);调用方须保证文件已被 truncate 到 fsync 过的字节位点。 */
  static ResumableExportFile append(Path path) throws IOException {
    return new ResumableExportFile(path, true);
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
