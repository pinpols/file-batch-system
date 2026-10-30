package io.github.pinpols.batch.trigger.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.web.request.TriggerLaunchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内部只读核验，不触发新请求。 */
@RestController
@RequestMapping("/internal/trigger/catch-up")
@RequiredArgsConstructor
public class InternalCatchUpVerificationController {
  private final TriggerService triggerService;

  @PostMapping("/verify")
  public CommonResponse<LaunchResponse> verify(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String key,
      @Valid @RequestBody TriggerLaunchRequest request) {
    return CommonResponse.success(
        triggerService.verifyCatchUpSubmission(new TriggerLaunchCommand(request, key, null, null)));
  }
}
