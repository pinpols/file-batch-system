package io.github.pinpols.batch.console.domain.ops.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.entity.MaintenanceStateEntity;
import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("维护状态 Mapper 启动解析")
class MaintenanceStateMapperXmlTest {

  @Test
  void mapsMaintenanceStateToExistingEntity() throws Exception {
    String resource = "mapper/MaintenanceStateMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream stream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(stream).as(resource).isNotNull();
      new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments())
          .parse();
    }
    assertThat(configuration
            .getResultMap(MaintenanceStateMapper.class.getName() + ".maintenanceStateMap")
            .getType())
        .isEqualTo(MaintenanceStateEntity.class);
    assertThat(
            configuration.hasStatement(MaintenanceStateMapper.class.getName() + ".selectSingleton"))
        .isTrue();
  }
}
