package io.github.pinpols.batch.trigger.service;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.trigger.mapper.ExternalEventDeliveryMapper;
import io.github.pinpols.batch.trigger.mapper.TriggerRequestMapper;
import java.sql.Connection;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 用受限连接执行真实 Mapper 和查询服务。 */
public final class TriggerCatchUpQueryTestHarness {
  private TriggerCatchUpQueryTestHarness() {}

  public static LaunchResponse verify(Connection connection, LaunchRequest expected, String key)
      throws Exception {
    var dataSource = new SingleConnectionDataSource(connection, true);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(dataSource);
    factory.setMapperLocations(
        new ClassPathResource("mapper/TriggerRequestMapper.xml"),
        new ClassPathResource("mapper/ExternalEventDeliveryMapper.xml"));
    var session = new SqlSessionTemplate(factory.getObject());
    var query = new TriggerLaunchQueryService(
        session.getMapper(TriggerRequestMapper.class),
        session.getMapper(ExternalEventDeliveryMapper.class),
        new DataSourceTransactionManager(dataSource));
    return query.verifyCatchUpSubmission(expected, key);
  }
}
