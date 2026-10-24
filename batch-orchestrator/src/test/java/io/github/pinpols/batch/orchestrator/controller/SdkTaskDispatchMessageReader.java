package io.github.pinpols.batch.orchestrator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.sdk.dispatcher.TaskDispatchMessage;
import java.io.IOException;

final class SdkTaskDispatchMessageReader {

  private SdkTaskDispatchMessageReader() {}

  static TaskDispatchMessage read(ObjectMapper mapper, byte[] payload) throws IOException {
    return mapper.readValue(payload, TaskDispatchMessage.class);
  }

  static TaskDispatchMessage read(ObjectMapper mapper, String payload) throws IOException {
    return mapper.readValue(payload, TaskDispatchMessage.class);
  }
}
