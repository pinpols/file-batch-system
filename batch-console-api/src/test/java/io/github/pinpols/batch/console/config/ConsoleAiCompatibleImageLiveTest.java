package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

@DisplayName("兼容协议多模态客户端冒烟: 图片与文本流式链路,依赖外部密钥方可执行")
class ConsoleAiCompatibleImageLiveTest {
  @Test
  @DisplayName("发送红绿测试图片后,流式回答描述出两种颜色且上报用量统计")
  void shouldDescribeBothColors_whenImageIsSentToCompatibleModel() throws Exception {
    ConsoleAiClients clients = deepSeekClients();

    Media image =
        Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(redGreenPng()).build();
    List<ChatResponse> chunks = clients
        .primary()
        .client()
        .prompt()
        .system("Describe only the colors visible in the user image.")
        .user(user -> user.text("Which two colors are visible?").media(image))
        .options(ChatOptions.builder().maxTokens(512))
        .stream()
        .chatResponse()
        .collectList()
        .block(Duration.ofSeconds(60));

    assertThat(chunks).isNotNull().isNotEmpty();
    String answer = chunks.stream()
        .flatMap(chunk -> chunk.getResults().stream())
        .map(generation -> generation.getOutput().getText())
        .filter(text -> text != null)
        .reduce("", String::concat)
        .toLowerCase();
    assertThat(answer).contains("red", "green");
    assertThat(chunks.stream()
            .map(ChatResponse::getMetadata)
            .filter(metadata -> metadata != null && metadata.getUsage() != null)
            .map(metadata -> metadata.getUsage().getTotalTokens()))
        .anyMatch(tokens -> tokens != null && tokens > 0);
  }

  @Test
  @DisplayName("发送纯文本提示后,流式回答包含约定标记且上报用量统计")
  void shouldReturnConventionMarker_whenTextPromptIsSentToSameAdapter() {
    ConsoleAiClients clients = deepSeekClients();
    List<ChatResponse> chunks = clients
        .primary()
        .client()
        .prompt()
        .system("Answer briefly in English.")
        .user("Reply with the exact token batch-ok.")
        .options(ChatOptions.builder().maxTokens(512))
        .stream()
        .chatResponse()
        .collectList()
        .block(Duration.ofSeconds(60));

    assertThat(chunks).isNotNull().isNotEmpty();
    String answer = chunks.stream()
        .flatMap(chunk -> chunk.getResults().stream())
        .map(generation -> generation.getOutput().getText())
        .filter(text -> text != null)
        .reduce("", String::concat)
        .toLowerCase();
    assertThat(answer).contains("batch-ok");
    assertThat(chunks.stream()
            .map(ChatResponse::getMetadata)
            .filter(metadata -> metadata != null && metadata.getUsage() != null)
            .map(metadata -> metadata.getUsage().getTotalTokens()))
        .anyMatch(tokens -> tokens != null && tokens > 0);
  }

  private static ConsoleAiClients deepSeekClients() {
    String apiKey = System.getenv("DEEPSEEK_API_KEY");
    Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank());
    ConsoleAiProperties properties = new ConsoleAiProperties();
    properties.setImageInputEnabled(true);
    properties.getOpenaiCompatible().setImageInputEnabled(true);
    properties.getOpenaiCompatible().setBaseUrl("https://api.deepseek.com");
    properties.getOpenaiCompatible().setApiKey(apiKey);
    properties.getOpenaiCompatible().setModel("deepseek-flash");
    properties.getOpenaiCompatible().setTimeout(Duration.ofSeconds(45));
    return ConsoleAiConfiguration.createOpenAiCompatibleClient(properties);
  }

  private static byte[] redGreenPng() throws Exception {
    BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        image.setRGB(x, y, x < 8 ? Color.RED.getRGB() : Color.GREEN.getRGB());
      }
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }
}
