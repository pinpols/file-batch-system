package io.github.pinpols.batch.common.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import java.util.List;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("可读性工厂行为:兼容占位与集合查询标量子句")
class ReadabilityFactoryBehaviorTest {

  @Test
  @DisplayName("空配置工厂保留旧领取响应的全空兼容语义")
  void shouldCreateEmptyEffectiveTaskConfig() {
    EffectiveTaskConfig config = EffectiveTaskConfig.empty();

    assertThat(config.jobCode()).isNull();
    assertThat(config.partitionInvocationId()).isNull();
  }

  @Test
  @DisplayName("集合查询的排序与分页表达式继续参与禁用函数扫描")
  void shouldInspectSetOperationScalarClauses() throws Exception {
    Statement statement =
        CCJSqlParserUtil.parse("select 1 union select 2 order by pg_sleep(1) limit 1 offset 1");

    assertThat(SelectSqlAstValidator.findForbiddenFunctionCall(statement, List.of("pg_sleep")))
        .isEqualTo("pg_sleep");
  }
}
