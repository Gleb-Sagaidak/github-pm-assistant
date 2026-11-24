package cz.cvut.pmassistant.llm;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cz.cvut.pmassistant.config.AppProperties;
import cz.cvut.pmassistant.llm.openai.OpenAiCompatibleLlmClient;
import cz.cvut.pmassistant.llm.openai.OpenAiCompatibleSettings;

/**
 * Makes the language model available as a {@link LlmClient}. The provider is whatever server speaks the
 * OpenAI chat-completions protocol at {@code app.llm.base-url}; the rest of the application only sees the port.
 * <p>
 * Besides the required {@code base-url} and {@code model} and the optional {@code api-key} (see
 * {@link AppProperties}), three optional settings tune the adapter: {@code app.llm.max-tokens-parameter}
 * ({@code max_tokens}, or {@code max_completion_tokens} for OpenAI's newer models), {@code app.llm.timeout} and
 * {@code app.llm.max-retries}.
 */
@Configuration(proxyBeanMethods = false)
public class LlmConfig {

	@Bean
	LlmClient llmClient(AppProperties properties,
			@Value("${app.llm.max-tokens-parameter:" + OpenAiCompatibleSettings.DEFAULT_MAX_TOKENS_PARAMETER
					+ "}") String maxTokensParameter,
			@Value("${app.llm.timeout:120s}") String timeout,
			@Value("${app.llm.max-retries:" + OpenAiCompatibleSettings.DEFAULT_MAX_RETRIES + "}") int maxRetries) {
		AppProperties.Llm llm = properties.llm();
		Duration parsedTimeout = DurationStyle.detectAndParse(timeout);
		return new OpenAiCompatibleLlmClient(new OpenAiCompatibleSettings(llm.baseUrl(), llm.apiKey(), llm.model(),
				maxTokensParameter, parsedTimeout, maxRetries));
	}
}
