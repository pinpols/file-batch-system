package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.command.ArrivalGroupGovernanceCommand;
import com.example.batch.orchestrator.domain.command.FileGovernanceCommand;

public interface FileGovernanceService {

    String archiveFile(FileGovernanceCommand command);

    String deleteFile(FileGovernanceCommand command);

    String presignFileDownload(FileGovernanceCommand command);

    String redispatchFile(FileGovernanceCommand command);

    String operateArrivalGroup(ArrivalGroupGovernanceCommand command);
}
