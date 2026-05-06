package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.service.governance.CompensationService;
import com.example.batch.orchestrator.domain.command.CompensationSubmitCommand;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 补偿任务提交内部控制器，基础路径 {@code /internal/compensations}。 提供单一端点 {@code POST
 * /internal/compensations}，接收补偿指令（包含补偿类型、目标实例、 业务日期、审批单号等字段），返回补偿命令编号（{@code commandNo}）。
 * 仅限内部服务或运维平台调用，不对外暴露。
 */
@RestController
@RequestMapping("/internal/compensations")
@RequiredArgsConstructor
public class CompensationController {

  private final CompensationService compensationService;

  @PostMapping
  public CompensationResponse submit(@RequestBody CompensationRequest request) {
    CompensationSubmitCommand command =
        CompensationSubmitCommand.builder()
            .tenantId(request.tenantId())
            .compensationType(request.compensationType())
            .targetId(request.targetId())
            .targetInstanceNo(request.targetInstanceNo())
            .jobCode(request.jobCode())
            .bizDate(request.bizDate())
            .batchNo(request.batchNo())
            .relatedFileId(request.relatedFileId())
            .channelCode(request.channelCode())
            .reason(request.reason())
            .operatorId(request.operatorId())
            .approvalId(request.approvalId())
            .strategy(request.strategy())
            .traceId(request.traceId())
            .resultPolicy(request.resultPolicy())
            .configVersionPolicy(request.configVersionPolicy())
            .configVersion(request.configVersion())
            .build();
    String compensationNo = compensationService.submit(command);
    return new CompensationResponse(compensationNo);
  }

  public record CompensationRequest(
      String tenantId,
      String compensationType,
      Long targetId,
      String targetInstanceNo,
      String jobCode,
      LocalDate bizDate,
      String batchNo,
      Long relatedFileId,
      String channelCode,
      String reason,
      String operatorId,
      String approvalId,
      String strategy,
      String traceId,
      String resultPolicy,
      String configVersionPolicy,
      Integer configVersion) {}

  public record CompensationResponse(String commandNo) {}
}
