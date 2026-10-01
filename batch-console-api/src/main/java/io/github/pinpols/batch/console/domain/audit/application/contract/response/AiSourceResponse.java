package io.github.pinpols.batch.console.domain.audit.application.contract.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/** AI 回答实际引用的知识库来源；不透出知识片段正文和内部相似度。 */
@Data
@AllArgsConstructor
public class AiSourceResponse {

  private String source;
}
