package io.github.pinpols.batch.orchestrator.application.service.dryrun;

import java.util.List;
import java.util.Map;

/** Dry-run 对象存储探测端口；具体 S3 SDK 访问由 infrastructure 实现。 */
public interface DryRunObjectStorageProbe {

  int probe(Map<String, Object> params, List<DryRunFinding> findings);
}
