package com.example.batch.orchestrator.application.service.governance;

import com.example.batch.orchestrator.domain.command.ArrivalGroupGovernanceCommand;
import com.example.batch.orchestrator.domain.command.FileGovernanceCommand;
import com.example.batch.orchestrator.domain.command.FileUploadSessionCommand;
import java.util.Map;

/** 文件治理服务。 提供文件归档、删除、预签名下载、重新分发以及到达批次组操作等生命周期管理能力。 所有操作均返回操作结果描述字符串；实现类须对越权访问进行租户隔离校验。 */
public interface FileGovernanceService {

  String archiveFile(FileGovernanceCommand command);

  String deleteFile(FileGovernanceCommand command);

  String presignFileDownload(FileGovernanceCommand command);

  Map<String, Object> createUploadSession(FileUploadSessionCommand command);

  String confirmFileArrival(FileGovernanceCommand command);

  String redispatchFile(FileGovernanceCommand command);

  String operateArrivalGroup(ArrivalGroupGovernanceCommand command);
}
