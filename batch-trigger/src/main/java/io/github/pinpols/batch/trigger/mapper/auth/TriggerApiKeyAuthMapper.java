package io.github.pinpols.batch.trigger.mapper.auth;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import io.github.pinpols.batch.trigger.auth.TriggerApiKeyCredential;

/** 在进程重启或遗漏上游完成通知后恢复延迟启动。 */
public interface TriggerApiKeyAuthMapper {

    List<TriggerApiKeyCredential> findActiveCandidatesByPrefixAndTenant(
            @Param("keyPrefix") String keyPrefix, @Param("tenantId") String tenantId);

    int touchLastUsedAt(@Param("id") Long id, @Param("tenantId") String tenantId);
}
