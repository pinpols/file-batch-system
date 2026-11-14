package io.github.pinpols.batch.sdk.handler.atomic;

import io.github.pinpols.batch.sdk.internal.EmptyChecks;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL / MariaDB 词法下的多语句切分器,只在 {@link SqlSplitDialect} 判定连接为 MySQL 家族时使用。
 *
 * <p>与 PostgreSQL 规则的差异:
 *
 * <ul>
 *   <li>{@code '...'} 与 {@code "..."} 是字符串,默认反斜杠转义下一个字符;会话开启 {@code NO_BACKSLASH_ESCAPES} 时反斜杠是字面字符。
 *       开启 {@code ANSI_QUOTES} 时 {@code "..."} 是标识符,反斜杠同样是字面字符。连写两个引号在各种引号里都是引号本身。
 *   <li>{@code `...`} 是标识符,只有连写反引号是转义。
 *   <li>{@code #} 开启行注释;{@code --} 只有后跟空白或控制字符(或位于串尾)才是行注释,{@code --1} 是两个减号。
 *   <li>块注释不嵌套,遇到第一个 {@code *}{@code /} 即结束;{@code /*!...*}{@code /} 可执行注释同样整体跳过,其中的 {@code ;}
 *       不切分。
 *   <li>没有 dollar-quote 与 {@code E'...'} 转义字符串({@code $} 是普通标识符字符)。
 * </ul>
 *
 * <p>客户端命令 {@code DELIMITER} 不属于服务端 SQL,不做识别。
 */
final class MySqlStatementSplitter {

  private MySqlStatementSplitter() {}

  /**
   * 按顶层 {@code ;} 切分,去掉首尾空白并丢弃空语句;未闭合的引号或注释吞到串尾。
   *
   * @param backslashEscapes 会话未开启 {@code NO_BACKSLASH_ESCAPES}
   * @param ansiQuotes 会话开启了 {@code ANSI_QUOTES}({@code "} 为标识符引号)
   */
  static List<String> split(String sql, boolean backslashEscapes, boolean ansiQuotes) {
    List<String> statements = new ArrayList<>();
    StringBuilder buffer = new StringBuilder();
    int i = 0;
    int n = sql.length();
    while (i < n) {
      char c = sql.charAt(i);
      int end = skipNonCode(sql, i, backslashEscapes, ansiQuotes);
      if (end > i) {
        buffer.append(sql, i, end);
        i = end;
      } else if (c == ';') {
        flush(buffer, statements);
        i++;
      } else {
        buffer.append(c);
        i++;
      }
    }
    flush(buffer, statements);
    return statements;
  }

  /** {@code start} 处若开启引号或注释,返回其结束位置(不含);否则返回 {@code start}。 */
  private static int skipNonCode(
      String sql, int start, boolean backslashEscapes, boolean ansiQuotes) {
    char c = sql.charAt(start);
    if (c == '\'') {
      return skipQuoted(sql, start, backslashEscapes);
    }
    if (c == '"') {
      return skipQuoted(sql, start, backslashEscapes && !ansiQuotes);
    }
    if (c == '`') {
      return skipQuoted(sql, start, false);
    }
    if (c == '#' || isDashDashComment(sql, start)) {
      int lineEnd = sql.indexOf('\n', start);
      return lineEnd < 0 ? sql.length() : lineEnd;
    }
    if (sql.startsWith("/*", start)) {
      int close = sql.indexOf("*/", start + 2);
      return close < 0 ? sql.length() : close + 2;
    }
    return start;
  }

  /** {@code --} 后须跟空白或控制字符(或位于串尾)才是注释。 */
  private static boolean isDashDashComment(String sql, int start) {
    if (!sql.startsWith("--", start)) {
      return false;
    }
    int next = start + 2;
    if (next >= sql.length()) {
      return true;
    }
    char following = sql.charAt(next);
    return Character.isWhitespace(following) || Character.isISOControl(following);
  }

  /** 从开引号跳到闭引号之后;连写引号继续字符串,{@code backslashEscapes} 时反斜杠跳过下一个字符。 */
  private static int skipQuoted(String sql, int start, boolean backslashEscapes) {
    char quote = sql.charAt(start);
    int i = start + 1;
    int n = sql.length();
    while (i < n) {
      char c = sql.charAt(i);
      if (backslashEscapes && c == '\\') {
        i += 2;
      } else if (c == quote && i + 1 < n && sql.charAt(i + 1) == quote) {
        i += 2;
      } else if (c == quote) {
        return i + 1;
      } else {
        i++;
      }
    }
    return n;
  }

  private static void flush(StringBuilder buffer, List<String> statements) {
    String rawStatement = buffer.toString();
    String statement = rawStatement.trim();
    if (!EmptyChecks.isEmpty(statement)) {
      statements.add(statement);
    }
    buffer.setLength(0);
  }
}
