package io.github.pinpols.batch.console.domain.job.mapper;

import org.apache.ibatis.annotations.Param;

import io.github.pinpols.batch.console.domain.job.param.BatchDayCatchUpOperationCreate;
import io.github.pinpols.batch.console.domain.job.param.BatchDayCatchUpOperationTransition;
import io.github.pinpols.batch.console.domain.job.view.BatchDayCatchUpOperation;

/** 批量日补跑原始请求和对账状态的持久化边界。 */
public interface BatchDayCatchUpOperationMapper {

    String setTenantContext(@Param("tenantId") String tenantId);

    int insertIfAbsent(BatchDayCatchUpOperationCreate operation);

    int insertAbandonedIfAbsent(BatchDayCatchUpOperationCreate operation);

    BatchDayCatchUpOperation select(@Param("tenantId") String tenantId, @Param("requestKeyHash") String requestKeyHash);

    int transition(BatchDayCatchUpOperationTransition transition);

    int resolveUnknown(BatchDayCatchUpOperationTransition transition);
}
