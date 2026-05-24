package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.ConsolePushApprovalNotificationEntity;
import com.example.batch.console.support.push.PendingApprovalNotification;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** {@code batch.console_push_approval_notification} MyBatis 映射。 */
public interface ConsolePushApprovalNotificationMapper {

  /**
   * 找出最近 lookbackMinutes 分钟内进入终态(APPROVED / REJECTED / EXECUTED)、 且有 requester_id 但尚未推送过的
   * approval_command。
   */
  List<PendingApprovalNotification> findPending(
      @Param("lookbackMinutes") int lookbackMinutes, @Param("batchSize") int batchSize);

  /** 幂等写入"已推送"记录。UNIQUE(tenant_id, approval_no) 冲突时 DO NOTHING,返回 0; 成功插入返回 1。 */
  int insertIgnore(ConsolePushApprovalNotificationEntity entity);
}
