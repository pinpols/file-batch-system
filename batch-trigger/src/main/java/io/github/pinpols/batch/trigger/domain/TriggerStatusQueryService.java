package io.github.pinpols.batch.trigger.domain;

import java.util.List;

/** Trigger 状态只读查询端口，与注册、暂停、恢复等写操作隔离。 */
public interface TriggerStatusQueryService {

  List<TriggerStatusInfo> listRegisteredTriggers();

  String schedulerStatus();
}
