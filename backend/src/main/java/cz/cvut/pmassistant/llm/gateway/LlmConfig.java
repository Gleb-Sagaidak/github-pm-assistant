package cz.cvut.pmassistant.llm.gateway;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cz.cvut.pmassistant.config.AppProperties;
import cz.cvut.pmassistant.llm.LlmClient;

/**
 * Makes the language model available as a {@link LlmClient}. Spring AI is the transport: its auto-configured
 * {@link ChatModel} is built from {@code spring.ai.openai.*}, which {@code application.yml} fills from
 * {@code app.llm.*} (that is, from {@code LLM_BASE_URL}, {@code LLM_API_KEY} and {@code LLM_MODEL}). Any
 * server that speaks the OpenAI chat-completions protocol works: OpenAI itself, Ollama, vLLM, LM Studio.
 * <p>
 * One optional setting belongs to the adapter: {@code app.llm.max-tokens-parameter} is {@code max_tokens}
 * (default, understood by most servers) or {@code max_completion_tokens} (required by OpenAI's newer models).
 * The rest of the application only sees the {@link LlmClient} port.
 */
@Configuration(proxyBeanMethods = false)
public class LlmConfig {

	@Bean
	LlmClient llmClient(ChatModel chatModel, AppProperties properties,
			@Value("${app.llm.max-tokens-parameter:max_tokens}") String maxTokensParameter) {
		return new GatewayLlmClient(chatModel, properties.llm().model(), new OpenAiErrorClassifier(),
				outputLimit(maxTokensParameter));
	}

	private static OutputLimit outputLimit(String parameter) {
		return switch (parameter) {
			case "max_tokens" -> OutputLimit.maxTokens();
			case "max_completion_tokens" -> OutputLimit.maxCompletionTokens();
			default -> throw new IllegalArgumentException("app.llm.max-tokens-parameter must be max_tokens or "
					+ "max_completion_tokens, not " + parameter);
		};
	}
}
