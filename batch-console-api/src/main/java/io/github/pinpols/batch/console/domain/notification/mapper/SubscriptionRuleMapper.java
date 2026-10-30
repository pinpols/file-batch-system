package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.param.SubscriptionRuleInsertParam;
import io.github.pinpols.batch.console.domain.notification.param.SubscriptionRuleUpdateParam;
import io.github.pinpols.batch.console.domain.notification.view.EnabledSubscriptionRuleRow;
import io.github.pinpols.batch.console.domain.notification.view.SubscriptionRuleRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface SubscriptionRuleMapper {

  List<SubscriptionRuleRow> selectByTenant(@Param("tenantId") String tenantId);

  /** 读取启用的租户候选，调用方必须执行事件类型及其余规则匹配。 */
  List<EnabledSubscriptionRuleRow> selectEnabledByEventType(
      @Param("tenantId") String tenantId, @Param("eventType") String eventType);

  SubscriptionRuleRow selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int insert(SubscriptionRuleInsertParam params);

  int update(SubscriptionRuleUpdateParam params);

  int deleteById(@Param("tenantId") String tenantId, @Param("id") Long id);
}
