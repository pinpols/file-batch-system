package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.orchestrator.domain.command.CompensationSubmitCommand;

/** 任务补偿服务。 对已失败或漏跑的 JobInstance 提交补偿重跑请求，返回新生成的补偿实例 ID。 实现类须校验补偿策略合法性，并确保补偿提交与 outbox 写入处于同一事务。 */
public interface CompensationService {

  String submit(CompensationSubmitCommand command);
}
