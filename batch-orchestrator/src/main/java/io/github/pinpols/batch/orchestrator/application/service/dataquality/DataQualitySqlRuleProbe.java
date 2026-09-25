package io.github.pinpols.batch.orchestrator.application.service.dataquality;

import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;

/** DQ SQL 规则执行端口。 */
public interface DataQualitySqlRuleProbe {

  long evaluateScalar(JobInstanceEntity instance, DataQualityRuleEntity rule);
}
