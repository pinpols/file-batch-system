package io.github.pinpols.batch.e2e;

import io.github.pinpols.batch.e2e.apps.E2eOrchestratorApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Separate JVM entry point for exercising the production stuck reconciler scheduler. */
public final class WorkflowStuckReconcilerChildProcess {

  private WorkflowStuckReconcilerChildProcess() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException("expected properties, ready marker, and stop marker paths");
    }
    Path propertiesPath = Path.of(args[0]);
    Path readyMarker = Path.of(args[1]);
    Path stopMarker = Path.of(args[2]);
    Properties properties = new Properties();
    try (var input = Files.newInputStream(propertiesPath)) {
      properties.load(input);
    }
    String[] applicationArgs = properties.stringPropertyNames().stream()
        .map(key -> "--" + key + "=" + properties.getProperty(key))
        .toArray(String[]::new);
    try (ConfigurableApplicationContext application = new SpringApplicationBuilder(
            E2eOrchestratorApplication.class)
        .profiles("test", "e2e")
        .web(WebApplicationType.NONE)
        .run(applicationArgs)) {
      Files.writeString(readyMarker, "started");
      while (!Files.exists(stopMarker)) {
        Thread.sleep(100L);
      }
    }
  }
}
