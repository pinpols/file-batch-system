package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.ApprovalCommandEntity;
import com.example.batch.console.domain.query.ApprovalCommandQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ApprovalCommandMapper {

  List<ApprovalCommandEntity> selectByQuery(ApprovalCommandQuery query);

  long countByQuery(ApprovalCommandQuery query);

  long countByStatus(
      @Param("tenantId") String tenantId, @Param("approvalStatus") String approvalStatus);
}
