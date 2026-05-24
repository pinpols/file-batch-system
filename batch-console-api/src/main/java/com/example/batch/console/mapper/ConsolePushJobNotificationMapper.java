package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.ConsolePushJobNotificationEntity;
import com.example.batch.console.support.push.PendingJobNotification;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** {@code batch.console_push_job_notification} MyBatis 映射。 */
public interface ConsolePushJobNotificationMapper {

  /**
   * 找出最近 lookbackMinutes 分钟内终态化、且有 operator_id 但尚未推送过的 job_instance。
   *
   * <p>SCHEDULED 等无 operator 的实例自然过滤掉(operator_id IS NULL)。
   */
  List<PendingJobNotification> findPending(
      @Param("lookbackMinutes") int lookbackMinutes, @Param("batchSize") int batchSize);

  /** 幂等写入"已推送"记录。UNIQUE(tenant_id, job_instance_id) 冲突时 DO NOTHING,返回 0; 成功插入返回 1。调用方依此判断是否首次推送。 */
  int insertIgnore(ConsolePushJobNotificationEntity entity);
}
