package io.github.pinpols.batch.orchestrator.application.service.dryrun;

import java.util.List;
import java.util.Map;

/** Dry-run SQL 探测端口。 */
public interface DryRunSqlProbe {

  int probe(Map<String, Object> params, List<DryRunFinding> findings);
}
