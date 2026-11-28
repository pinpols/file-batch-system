package io.github.pinpols.batch.console.domain.ops.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import io.github.pinpols.batch.console.domain.ops.entity.ApprovalCommandEntity;
import io.github.pinpols.batch.console.domain.ops.query.ApprovalCommandQuery;

public interface ApprovalCommandMapper {

    List<ApprovalCommandEntity> selectByQuery(ApprovalCommandQuery query);

    long countByQuery(ApprovalCommandQuery query);

    long countByStatus(@Param("tenantId") String tenantId, @Param("approvalStatus") String approvalStatus);
}
