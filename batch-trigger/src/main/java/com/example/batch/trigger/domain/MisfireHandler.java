package com.example.batch.trigger.domain;

public interface MisfireHandler {

  void handle(String triggerName);
}
