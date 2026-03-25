package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.ApprovalCommandEntity;
import com.example.batch.console.domain.query.ApprovalCommandQuery;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ApprovalCommandMapper {

    List<ApprovalCommandEntity> selectByQuery(ApprovalCommandQuery query);

    long countByStatus(@Param("tenantId") String tenantId, @Param("approvalStatus") String approvalStatus);
}

