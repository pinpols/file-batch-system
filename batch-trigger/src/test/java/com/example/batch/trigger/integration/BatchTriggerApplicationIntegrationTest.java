package com.example.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.flyway.enabled=false",
      "spring.quartz.job-store-type=jdbc",
      "spring.quartz.jdbc.initialize-schema=always"
    })
class BatchTriggerApplicationIntegrationTest extends AbstractIntegrationTest {

  @Autowired Scheduler scheduler;

  @Test
  void contextLoads() {
    assertThat(scheduler).isNotNull();
  }

  @Test
  void quartzSchedulerStarted() throws Exception {
    assertThat(scheduler.isStarted()).isTrue();
  }
}
