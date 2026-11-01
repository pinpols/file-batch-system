package io.github.pinpols.batch.worker.core.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.worker.core.domain.entity.PipelineProgressEntity;
import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Worker 流水线进度 Mapper 启动解析")
class PipelineProgressMapperXmlTest {

  @Test
  @DisplayName("流水线进度结果映射及查询语句可正确解析")
  void shouldMapPipelineProgressToExistingEntity() throws Exception {
    String resource = "mapper/PipelineProgressMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream stream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(stream).as(resource).isNotNull();
      new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments())
          .parse();
    }
    assertThat(configuration
            .getResultMap(PipelineProgressMapper.class.getName() + ".pipelineProgressResult")
            .getType())
        .isEqualTo(PipelineProgressEntity.class);
    assertThat(configuration.hasStatement(
            PipelineProgressMapper.class.getName() + ".findByInstanceAndStage"))
        .isTrue();
  }
}
