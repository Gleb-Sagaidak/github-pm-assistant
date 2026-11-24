package cz.cvut.pmassistant.llm.openai;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings of an OpenAI-compatible endpoint (OpenAI itself, or a server speaking the same protocol such as Ollama,
 * vLLM or LM Studio).
 *
 * @param baseUrl            up to and including the version path, e.g. {@code https://api.openai.com/v1} or
 *                           {@code http://localhost:11434/v1}; {@code /chat/completions} is appended
 * @param apiKey             bearer token; null or blank for servers that need none
 * @param maxTokensParameter name of the request field that limits the answer: {@code max_tokens} (accepted by
 *                           most servers) or {@code max_completion_tokens} (required by OpenAI's newer models)
 * @param timeout            how long to wait for one answer; answers are not streamed
 * @param maxRetries         extra attempts after a rate limit, a server error or a network failure
 */
public record OpenAiCompatibleSettings(String baseUrl, String apiKey, String model, String maxTokensParameter,
		Duration timeout, int maxRetries) {

	public static final String DEFAULT_MAX_TOKENS_PARAMETER = "max_tokens";
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
	public static final int DEFAULT_MAX_RETRIES = 2;

	public OpenAiCompatibleSettings {
		Objects.requireNonNull(baseUrl, "baseUrl");
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(maxTokensParameter, "maxTokensParameter");
		Objects.requireNonNull(timeout, "timeout");
		if (baseUrl.isBlank() || model.isBlank()) {
			throw new IllegalArgumentException("The base URL and the model must be set");
		}
		if (timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("The timeout must be positive");
		}
		if (maxRetries < 0) {
			throw new IllegalArgumentException("maxRetries cannot be negative");
		}
		baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
	}

	public static OpenAiCompatibleSettings of(String baseUrl, String apiKey, String model) {
		return new OpenAiCompatibleSettings(baseUrl, apiKey, model, DEFAULT_MAX_TOKENS_PARAMETER, DEFAULT_TIMEOUT,
				DEFAULT_MAX_RETRIES);
	}

	boolean hasApiKey() {
		return apiKey != null && !apiKey.isBlank();
	}

	/** The secret never appears when the settings are printed. */
	@Override
	public String toString() {
		return "OpenAiCompatibleSettings[baseUrl=" + baseUrl + ", model=" + model + ", apiKey="
				+ (hasApiKey() ? "<set>" : "<none>") + ", maxTokensParameter=" + maxTokensParameter + ", timeout="
				+ timeout + ", maxRetries=" + maxRetries + "]";
	}
}
