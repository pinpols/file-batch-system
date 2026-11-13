package io.github.pinpols.batch.worker.atomic.sql;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link SqlTaskExecutor} 的多语句切分器:按顶层 `;` 把 SQL 文本切成有序语句列表。
 *
 * <p>词法按 PostgreSQL 规则处理引号、注释与 dollar-quote,详见 {@link #split(String)}。无状态工具类,不可实例化。
 */
final class SqlStatementSplitter {

  private SqlStatementSplitter() {}

  /**
   * 用 `;` 切语句,跳过引号内 / 注释内 / PG dollar-quote 体内的 `;`。dollar-quote(<code>$$...$$</code> / <code>
   * $tag$...$tag$</code>)体内含 `;` 不再被误切(PG 函数体、含分号的字面量常用),修正原"不处理 dollar-quote" 导致的
   * maxStatementsPerJob / 只读判定偏差。
   */
  static List<String> split(String sql) {
    SplitScanner sc = new SplitScanner();
    char prev = 0;
    int i = 0;
    int n = sql.length();
    while (i < n) {
      char c = sql.charAt(i);
      // 顶层(非引号/注释内)的 dollar-quote 开标签:整段(含开/闭标签)一次性消费,体内 ; 引号 注释全字面
      if (sc.atTopLevel() && c == '$') {
        String openTag = matchDollarTag(sql, i);
        if (EmptyChecks.isNotNull(openTag)) {
          i = consumeDollarQuote(sql, i, openTag, sc.buf);
          prev = '$';
          continue;
        }
      }
      prev = sc.feed(c, prev);
      i++;
    }
    return sc.finish();
  }

  /**
   * {@link #split} 的逐字符状态机,把引号/注释/分号状态与 buffer 封装,避免单方法 NCSS 过高。
   *
   * <p>引号词法按 PostgreSQL(standard_conforming_strings=on,自 9.1 起默认)处理:普通 {@code '...'} 与 {@code
   * "..."} 内反斜杠是字面字符,唯一的引号转义是连写两个引号;只有 {@code E'...'}/{@code e'...'} 转义字符串里反斜杠转义下一个字符。
   *
   * <p>块注释按 PostgreSQL 规则可嵌套:{@code /* a /* b *}{@code / c *}{@code /} 整体是一段注释,注释内每个 {@code /*}
   * 加一层、每个 {@code *}{@code /} 减一层,回到 0 层才结束。{@code /*}、{@code *}{@code /}、{@code --} 是两字符记号,已组成记号的
   * 第二个字符不再与下一个字符配对(如 {@code /*}{@code /} 不是开即闭,{@code *}{@code /*} 是收尾后跟乘号)。
   */
  private static final class SplitScanner {
    private final List<String> out = new ArrayList<>();
    private final StringBuilder buf = new StringBuilder();

    /** 词法状态:同一时刻只处于其一。 */
    private enum LexState {
      CODE,
      LINE_COMMENT,
      BLOCK_COMMENT,
      SINGLE_QUOTE,
      ESCAPE_STRING,
      ESCAPE_STRING_BACKSLASH,
      DOUBLE_QUOTE
    }

    private LexState state = LexState.CODE;

    /** 当前块注释嵌套层数;仅在 {@link LexState#BLOCK_COMMENT} 下大于 0。 */
    private int blockCommentDepth;

    /** 最近一次收尾的单引号字符串种类;紧跟的引号属于连写引号转义,需回到同一种字符串。 */
    private LexState lastSingleQuoteKind = LexState.SINGLE_QUOTE;

    /** {@code prev} 之前的字符,用于判断 {@code E'} 前缀是否独立成词(而非标识符结尾的 e)。 */
    private char beforePrev;

    boolean atTopLevel() {
      return state == LexState.CODE;
    }

    /**
     * 消费一个字符,返回下一个字符应看到的"前一字符":{@code c} 与 {@code prev} 组成两字符记号时返回 0,避免记号的第二个字符被重复配对。
     */
    char feed(char c, char prev) {
      boolean split = false;
      boolean pairConsumed = false;
      switch (state) {
        case LINE_COMMENT -> {
          if (c == '\n') state = LexState.CODE;
        }
        case BLOCK_COMMENT -> pairConsumed = feedBlockComment(c, prev);
        case SINGLE_QUOTE, ESCAPE_STRING, ESCAPE_STRING_BACKSLASH -> feedSingleQuoted(c);
        case DOUBLE_QUOTE -> {
          if (c == '"') state = LexState.CODE;
        }
        case CODE -> {
          split = c == ';';
          pairConsumed = feedTopLevel(c, prev);
        }
      }
      beforePrev = prev;
      if (!split) {
        buf.append(c); // 分号已切分时不入 buffer
      }
      return pairConsumed ? 0 : c;
    }

    /** 块注释内部:{@code /*} 加一层、{@code *}{@code /} 减一层,回到 0 层时结束注释;返回是否组成了两字符记号。 */
    private boolean feedBlockComment(char c, char prev) {
      if (prev == '/' && c == '*') {
        blockCommentDepth++;
        return true;
      }
      if (prev == '*' && c == '/') {
        blockCommentDepth--;
        if (blockCommentDepth == 0) state = LexState.CODE;
        return true;
      }
      return false;
    }

    /** 单引号字符串内部:只有转义字符串识别反斜杠,收尾时记下种类以便处理连写引号。 */
    private void feedSingleQuoted(char c) {
      if (state == LexState.ESCAPE_STRING_BACKSLASH) {
        state = LexState.ESCAPE_STRING;
      } else if (state == LexState.ESCAPE_STRING && c == '\\') {
        state = LexState.ESCAPE_STRING_BACKSLASH;
      } else if (c == '\'') {
        lastSingleQuoteKind = state;
        state = LexState.CODE;
      }
    }

    /** 顶层字符:开注释/开引号切换状态,遇 `;` 收尾当前语句;返回是否组成了注释开记号。 */
    private boolean feedTopLevel(char c, char prev) {
      if (c == '-' && prev == '-') {
        state = LexState.LINE_COMMENT;
        return true;
      } else if (c == '*' && prev == '/') {
        state = LexState.BLOCK_COMMENT;
        blockCommentDepth = 1;
        return true;
      } else if (c == '\'') {
        state = openingSingleQuoteKind(prev);
      } else if (c == '"') {
        state = LexState.DOUBLE_QUOTE;
      } else if (c == ';') {
        flush();
      }
      return false;
    }

    /** 判断新出现的单引号开启哪种字符串:连写引号延续上一段,独立的 E/e 前缀开启转义字符串。 */
    private LexState openingSingleQuoteKind(char prev) {
      if (prev == '\'') {
        return lastSingleQuoteKind;
      }
      boolean escapePrefix = (prev == 'E' || prev == 'e') && !isIdentifierPart(beforePrev);
      return escapePrefix ? LexState.ESCAPE_STRING : LexState.SINGLE_QUOTE;
    }

    private static boolean isIdentifierPart(char c) {
      return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private void flush() {
      String bufferedStatement = buf.toString();
      String s = bufferedStatement.trim();
      if (EmptyChecks.isNotEmpty(s)) out.add(s);
      buf.setLength(0);
    }

    List<String> finish() {
      flush();
      return out;
    }
  }

  /** 从 dollar-quote 开标签处把整段(含开/闭标签)写入 {@code buf},返回闭标签之后的索引; 未闭合(到串尾仍无闭标签)则全部写入并返回串尾。 */
  private static int consumeDollarQuote(String sql, int start, String tag, StringBuilder buf) {
    buf.append(tag);
    int i = start + tag.length();
    int n = sql.length();
    while (i < n) {
      if (sql.charAt(i) == '$' && sql.startsWith(tag, i)) {
        buf.append(tag);
        return i + tag.length();
      }
      buf.append(sql.charAt(i));
      i++;
    }
    return n;
  }

  /**
   * 在 {@code pos}(此处字符为 {@code $})尝试匹配 PG dollar-quote 开标签 {@code $tag$}: tag 为空或 {@code
   * [A-Za-z_][A-Za-z0-9_]*}。返回含两端 {@code $} 的完整标签串(如 {@code $$} / {@code $body$}); 不是合法
   * dollar-quote(如位置参数 {@code $1}、运算)返回 null。
   */
  private static String matchDollarTag(String sql, int pos) {
    int n = sql.length();
    for (int j = pos + 1; j < n; j++) {
      char c = sql.charAt(j);
      if (c == '$') {
        String tag = sql.substring(pos + 1, j);
        return (EmptyChecks.isEmpty(tag) || isValidDollarTag(tag))
            ? sql.substring(pos, j + 1)
            : null;
      }
      if (!(Character.isLetterOrDigit(c) || c == '_')) {
        return null; // 标签里出现非法字符(空格/运算符)→ 普通 $,非 dollar-quote
      }
    }
    return null;
  }

  private static boolean isValidDollarTag(String tag) {
    if (Character.isDigit(tag.charAt(0))) {
      return false; // 标识符不能以数字开头($1 是位置参数,不是 dollar-quote)
    }
    for (int k = 0; k < tag.length(); k++) {
      char c = tag.charAt(k);
      if (!(Character.isLetterOrDigit(c) || c == '_')) {
        return false;
      }
    }
    return true;
  }
}
