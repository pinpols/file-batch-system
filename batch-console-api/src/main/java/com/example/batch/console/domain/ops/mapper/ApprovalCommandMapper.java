package com.example.batch.console.domain.ops.mapper;

import com.example.batch.console.domain.ops.entity.ApprovalCommandEntity;
import com.example.batch.console.domain.ops.query.ApprovalCommandQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ApprovalCommandMapper {

  List<ApprovalCommandEntity> selectByQuery(ApprovalCommandQuery query);

  long countByQuery(ApprovalCommandQuery query);

  long countByStatus(
      @Param("tenantId") String tenantId, @Param("approvalStatus") String approvalStatus);
}
