package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobTaskMapper {

    int insert(JobTaskEntity entity);

    JobTaskEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

    JobTaskEntity selectByPartitionAndSeq(@Param("tenantId") String tenantId,
                                          @Param("jobPartitionId") Long jobPartitionId,
                                          @Param("taskSeq") Integer taskSeq);

    List<JobTaskEntity> selectByQuery(JobTaskQuery query);

    List<JobTaskEntity> selectReadyTasks(@Param("tenantId") String tenantId,
                                         @Param("batchSize") int batchSize,
                                         @Param("readyStatus") String readyStatus);

    List<JobTaskEntity> selectActiveByAssignedWorker(@Param("tenantId") String tenantId,
                                                     @Param("assignedWorkerCode") String assignedWorkerCode,
                                                     @Param("runningStatus") String runningStatus,
                                                     @Param("readyStatus") String readyStatus,
                                                     @Param("createdStatus") String createdStatus);

    int updateStatus(UpdateTaskStatusParam param);

    int assignWorker(@Param("tenantId") String tenantId,
                     @Param("id") Long id,
                     @Param("assignedWorkerCode") String assignedWorkerCode,
                     @Param("taskStatus") String taskStatus,
                     @Param("readyStatus") String readyStatus,
                     @Param("expectedVersion") Long expectedVersion);

    int resetForRetry(@Param("tenantId") String tenantId,
                      @Param("id") Long id,
                      @Param("readyStatus") String readyStatus,
                      @Param("expectedVersion") Long expectedVersion);

    int promoteStatus(@Param("tenantId") String tenantId,
                      @Param("id") Long id,
                      @Param("fromStatus") String fromStatus,
                      @Param("toStatus") String toStatus,
                      @Param("expectedVersion") Long expectedVersion);

    int finishTask(FinishTaskParam param);
}
