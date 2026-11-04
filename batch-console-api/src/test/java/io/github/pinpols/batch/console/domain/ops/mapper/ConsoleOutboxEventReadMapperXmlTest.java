package io.github.pinpols.batch.console.domain.ops.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.pinpols.batch.console.domain.ops.view.OutboxEventStatusStat;
import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Outbox 状态统计投影: 构造参数类型与 record 一致")
class ConsoleOutboxEventReadMapperXmlTest {

  private static final String MAPPER = "mapper/ConsoleOutboxEventReadMapper.xml";
  private static final String NAMESPACE =
      "io.github.pinpols.batch.console.domain.ops.mapper.ConsoleOutboxEventReadMapper";

  /**
   * MyBatis 按 &lt;arg javaType&gt; 精确查找构造器；别名 long 指包装类型 Long，原始类型需写 _long。
   * 写错时只有运行到该查询才抛 NoSuchMethodException（Outbox 状态统计接口因此返回 500）。
   */
  @Test
  @DisplayName("resultMap 声明的构造参数类型能找到 OutboxEventStatusStat 的构造器")
  void shouldResolveRecordConstructorFromDeclaredArgTypes() throws Exception {
    Configuration configuration = new Configuration();
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(MAPPER)) {
      assertThat(inputStream).as(MAPPER).isNotNull();
      new XMLMapperBuilder(inputStream, configuration, MAPPER, configuration.getSqlFragments())
          .parse();
    }
    ResultMap resultMap = configuration.getResultMap(NAMESPACE + ".OutboxEventStatusStatMap");
    Class<?>[] argTypes = resultMap.getConstructorResultMappings().stream()
        .map(ResultMapping::getJavaType)
        .toArray(Class<?>[]::new);

    assertThatCode(() -> OutboxEventStatusStat.class.getDeclaredConstructor(argTypes))
        .doesNotThrowAnyException();
  }
}
