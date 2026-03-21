package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.DeadLetterTaskEntity;
import org.apache.ibatis.annotations.Param;

public interface DeadLetterTaskMapper {

    int insert(DeadLetterTaskEntity entity);

    DeadLetterTaskEntity selectById(@Param("tenantId") String tenantId,
                                    @Param("id") Long id);

    int markReplaying(@Param("tenantId") String tenantId,
                      @Param("id") Long id,
                      @Param("expectedStatus") String expectedStatus,
                      @Param("targetStatus") String targetStatus);

    int markReplaySuccess(@Param("tenantId") String tenantId,
                          @Param("id") Long id,
                          @Param("replayCount") Integer replayCount,
                          @Param("lastReplayAt") java.time.Instant lastReplayAt,
                          @Param("lastReplayResult") String lastReplayResult);

    int markReplayFailure(@Param("tenantId") String tenantId,
                          @Param("id") Long id,
                          @Param("targetStatus") String targetStatus,
                          @Param("replayCount") Integer replayCount,
                          @Param("lastReplayAt") java.time.Instant lastReplayAt,
                          @Param("lastReplayResult") String lastReplayResult);
}
