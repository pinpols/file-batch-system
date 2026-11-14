package io.github.pinpols.batch.worker.atomic.sql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link SqlStatementSplitter} 单测:引号、注释与美元引用内的分号不误切。 */
@DisplayName("语句切分: 引号, 注释与美元引用内的分号不误切")
class SqlStatementSplitterTest {

  @Test
  @DisplayName("语句末尾没有分号时应作为单条语句返回")
  void shouldReturnSingleStatement_whenNoSemicolon() {
    assertThat(SqlStatementSplitter.split("SELECT 1")).containsExactly("SELECT 1");
  }

  @Test
  @DisplayName("多条语句应按分号切分为有序列表")
  void shouldSplit_whenMultipleStatementsPresent() {
    assertThat(SqlStatementSplitter.split("SELECT 1; SELECT 2; SELECT 3;"))
        .containsExactly("SELECT 1", "SELECT 2", "SELECT 3");
  }

  @Test
  @DisplayName("单引号字面量内的分号不应触发切分")
  void shouldKeepSemicolon_whenInsideSingleQuotes() {
    assertThat(SqlStatementSplitter.split("SELECT 'a;b'; SELECT 2"))
        .containsExactly("SELECT 'a;b'", "SELECT 2");
  }

  @Test
  @DisplayName("双引号标识符内的分号不应触发切分")
  void shouldKeepSemicolon_whenInsideDoubleQuotes() {
    assertThat(SqlStatementSplitter.split("SELECT \"col;name\" FROM t; SELECT 2"))
        .containsExactly("SELECT \"col;name\" FROM t", "SELECT 2");
  }

  @Test
  @DisplayName("行注释内的分号不应触发切分")
  void shouldKeepSemicolon_whenInsideLineComment() {
    assertThat(SqlStatementSplitter.split("""
        SELECT 1 -- comment ; not split
        ; SELECT 2
        """.stripTrailing())).hasSize(2);
  }

  @Test
  @DisplayName("块注释内的分号不应触发切分")
  void shouldKeepSemicolon_whenInsideBlockComment() {
    assertThat(SqlStatementSplitter.split("SELECT 1 /* comment ; not split */; SELECT 2"))
        .hasSize(2);
  }

  @Test
  @DisplayName("连续分号产生的空语句应被跳过, 只保留有效语句")
  void shouldSkipEmptyStatements_whenConsecutiveSemicolons() {
    assertThat(SqlStatementSplitter.split("SELECT 1;;;SELECT 2;"))
        .containsExactly("SELECT 1", "SELECT 2");
  }

  @Test
  @DisplayName("美元引用体内的分号不应触发切分")
  void shouldKeepSemicolon_whenInsideDollarQuotedBody() {
    // $$...$$ 体内的 ; 不应被误切(PG 函数体/含分号字面量常见)
    assertThat(SqlStatementSplitter.split("SELECT $$a;b;c$$ AS v; SELECT 2"))
        .containsExactly("SELECT $$a;b;c$$ AS v", "SELECT 2");
  }

  @Test
  @DisplayName("带标签的美元引用体内的分号不应触发切分")
  void shouldKeepSemicolon_whenInsideTaggedDollarQuote() {
    assertThat(SqlStatementSplitter.split(
            "DO $body$ BEGIN PERFORM 1; PERFORM 2; END $body$; SELECT 9"))
        .containsExactly("DO $body$ BEGIN PERFORM 1; PERFORM 2; END $body$", "SELECT 9");
  }

  @Test
  @DisplayName("位置参数形式不应被误判为美元引用, 分号仍应正常切分")
  void shouldSplit_whenDollarIsPositionalParam() {
    // $1 是位置参数,不是 dollar-quote 开标签;此处 ; 仍应正常切分
    assertThat(SqlStatementSplitter.split("SELECT $1 WHERE a=$1; SELECT 2"))
        .containsExactly("SELECT $1 WHERE a=$1", "SELECT 2");
  }

  @Test
  @DisplayName("只有顶层分号切分语句, 前后空白被裁掉")
  void shouldSplitOnTopLevelSemicolonOnly() {
    assertThat(SqlStatementSplitter.split("select 1; select 2"))
        .containsExactly("select 1", "select 2");
  }

  @Test
  @DisplayName("引号与注释混用时各自内部的分号都应保留")
  void shouldKeepSemicolon_whenQuotesAndCommentsMixed() {
    assertThat(SqlStatementSplitter.split("select ';' -- a;b\n; select \"x;y\" /* ; */"))
        .containsExactly("select ';' -- a;b", "select \"x;y\" /* ; */");
  }

  @Test
  @DisplayName("纯分号与空白产生的空语句应被丢弃")
  void shouldDropEmptyStatements() {
    assertThat(SqlStatementSplitter.split(";;select 1;")).containsExactly("select 1");
  }

  @Test
  @DisplayName("普通单引号字符串内的反斜杠是字面字符, 其后的引号照常收尾")
  void shouldTreatBackslashAsLiteral_whenInsideStandardString() {
    // standard_conforming_strings=on(PG 默认): 'C:\' 是完整字面量, 后面的 delete 是独立语句
    assertThat(SqlStatementSplitter.split("insert into t values ('C:\\'); delete from t"))
        .containsExactly("insert into t values ('C:\\')", "delete from t");
  }

  @Test
  @DisplayName("双引号标识符内的反斜杠是字面字符, 其后的双引号照常收尾")
  void shouldTreatBackslashAsLiteral_whenInsideDoubleQuotes() {
    assertThat(SqlStatementSplitter.split("select 1 as \"x\\\"; select 2"))
        .containsExactly("select 1 as \"x\\\"", "select 2");
  }

  @Test
  @DisplayName("两个连续单引号仍是普通字符串内的引号转义")
  void shouldKeepDoubledQuote_whenInsideStandardString() {
    assertThat(SqlStatementSplitter.split("select 'it''s;ok\\'; select 2"))
        .containsExactly("select 'it''s;ok\\'", "select 2");
  }

  @Test
  @DisplayName("转义字符串内的反斜杠加引号按转义处理, 分号不触发切分")
  void shouldTreatBackslashQuoteAsEscape_whenInsideEscapeString() {
    assertThat(SqlStatementSplitter.split("select E'it\\'s;ok'; select 2"))
        .containsExactly("select E'it\\'s;ok'", "select 2");
  }

  @Test
  @DisplayName("小写前缀的转义字符串中, 转义后的反斜杠不再吞掉收尾引号")
  void shouldCloseEscapeString_whenBackslashItselfEscaped() {
    assertThat(SqlStatementSplitter.split("select e'a\\\\'; select 2"))
        .containsExactly("select e'a\\\\'", "select 2");
  }

  @Test
  @DisplayName("转义字符串内连续两个单引号后仍保持转义语义")
  void shouldKeepEscapeSemantics_whenEscapeStringContainsDoubledQuote() {
    assertThat(SqlStatementSplitter.split("select E'it''s\\';x'; select 2"))
        .containsExactly("select E'it''s\\';x'", "select 2");
  }

  @Test
  @DisplayName("以 e 结尾的标识符后紧跟引号时仍是普通字符串")
  void shouldTreatAsStandardString_whenQuoteFollowsIdentifierEndingWithE() {
    assertThat(SqlStatementSplitter.split("select time'12:00\\'; select 2"))
        .containsExactly("select time'12:00\\'", "select 2");
  }

  @Test
  @DisplayName("美元引用与注释内的反斜杠不影响切分")
  void shouldIgnoreBackslash_whenInsideDollarQuoteOrComment() {
    assertThat(SqlStatementSplitter.split("select $$a\\';$$ /* \\' ; */ -- \\' ;\n; select 2"))
        .containsExactly("select $$a\\';$$ /* \\' ; */ -- \\' ;", "select 2");
  }

  @Test
  @DisplayName("嵌套块注释整体是一段注释, 内层注释收尾后的分号仍不触发切分")
  void shouldKeepSemicolon_whenInsideNestedBlockComment() {
    assertThat(SqlStatementSplitter.split(
            "select 1 /* outer ; /* inner ; */ still comment ; */ ; select 2"))
        .containsExactly("select 1 /* outer ; /* inner ; */ still comment ; */", "select 2");
  }

  @Test
  @DisplayName("多层嵌套块注释应逐层收尾, 全部收尾后分号恢复切分")
  void shouldTrackDepth_whenBlockCommentsNestSeveralLevels() {
    assertThat(SqlStatementSplitter.split("select 3 /* a /* b /* c ; */ d ; */ e ; */; select 4"))
        .containsExactly("select 3 /* a /* b /* c ; */ d ; */ e ; */", "select 4");
  }

  @Test
  @DisplayName("注释开标签的星号不能与后面的斜杠组成收尾标记")
  void shouldNotCloseComment_whenOpeningStarIsFollowedBySlash() {
    assertThat(SqlStatementSplitter.split("select 1 /*/ ; */ ; select 2"))
        .containsExactly("select 1 /*/ ; */", "select 2");
  }

  @Test
  @DisplayName("注释收尾标记的斜杠不能与后面的星号组成新的注释开标签")
  void shouldNotReopenComment_whenClosingSlashIsFollowedByStar() {
    assertThat(SqlStatementSplitter.split("select 3 /* x */*2 as v; select 4"))
        .containsExactly("select 3 /* x */*2 as v", "select 4");
  }

  @Test
  @DisplayName("引号与行注释内的注释开标签不计入嵌套层数")
  void shouldIgnoreCommentOpener_whenInsideQuoteOrLineComment() {
    assertThat(SqlStatementSplitter.split("select '/*' as d; select 6 -- /*\n; select 7"))
        .containsExactly("select '/*' as d", "select 6 -- /*", "select 7");
  }
}
