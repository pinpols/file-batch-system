package com.example.batch.console.support;

import com.example.batch.console.domain.command.AiAuditCommand;

public interface ConsoleAiAuditService {

    void record(AiAuditCommand command);
}
