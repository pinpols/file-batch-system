package io.github.pinpols.batch.console.support.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.ConsoleMaintenanceProperties;
import io.github.pinpols.batch.console.domain.ops.mapper.MaintenanceStateMapper;

/** 测试支撑: standalone 场景下替代容器完成 {@code @PostConstruct} 初始化, 避免跨包反射调用包内可见方法。 */
public final class MaintenanceStateHolderTestSupport {

  private MaintenanceStateHolderTestSupport() {}

  /** 构造持有者并执行与容器一致的初始化(取配置初值, 不访问数据库)。 */
  public static MaintenanceStateHolder initialized(
      ConsoleMaintenanceProperties properties, MaintenanceStateMapper mapper) {
    MaintenanceStateHolder holder =
        new MaintenanceStateHolder(properties, mapper, new ObjectMapper());
    holder.initFromProperties();
    return holder;
  }
}
