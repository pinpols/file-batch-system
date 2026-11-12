package io.github.pinpols.batch.common.sql;

import static org.assertj.core.api.Assertions.assertThat;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SQL 函数名收集:补走 TablesNamesFinder 缺席的子句")
class SqlFunctionNameCollectorTest {

  @Test
  @DisplayName("GROUP BY 与 HAVING 中的函数被收集")
  void collect_includesFunctionsInGroupByAndHaving() throws Exception {
    Statement statement =
        CCJSqlParserUtil.parse("select a from t group by lower(a) having count(*) > 1");

    assertThat(SqlFunctionNameCollector.collect(statement)).contains("lower", "count");
  }

  @Test
  @DisplayName("集合查询 ORDER BY 中的函数被收集")
  void collect_includesFunctionsInSetOperationOrderBy() throws Exception {
    Statement statement = CCJSqlParserUtil.parse(
        "select a from t union select a from u order by pg_terminate_backend(1)");

    assertThat(SqlFunctionNameCollector.collect(statement)).contains("pg_terminate_backend");
  }

  @Test
  @DisplayName("LIMIT 与 OFFSET 中的函数被收集")
  void collect_includesFunctionsInLimitAndOffset() throws Exception {
    Statement statement = CCJSqlParserUtil.parse("select a from t limit abs(1) offset ceil(2)");

    assertThat(SqlFunctionNameCollector.collect(statement)).contains("abs", "ceil");
  }

  @Test
  @DisplayName("集合查询 LIMIT 与 OFFSET 中的函数被收集")
  void collect_includesFunctionsInSetOperationLimitAndOffset() throws Exception {
    Statement statement =
        CCJSqlParserUtil.parse("select a from t union select a from u limit abs(1) offset ceil(2)");

    assertThat(SqlFunctionNameCollector.collect(statement)).contains("abs", "ceil");
  }

  @Test
  @DisplayName("带引号的函数名被去引号并转小写")
  void collect_normalizesQuotedNames() throws Exception {
    Statement statement = CCJSqlParserUtil.parse("select \"PG_READ_SERVER_FILES\"('x')");

    assertThat(SqlFunctionNameCollector.collect(statement)).contains("pg_read_server_files");
  }
}
