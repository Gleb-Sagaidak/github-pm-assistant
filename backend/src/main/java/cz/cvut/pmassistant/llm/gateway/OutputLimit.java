package cz.cvut.pmassistant.llm.gateway;

import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * How the limit on the length of an answer is put into the request. OpenAI's newer models reject
 * {@code max_tokens} and want {@code max_completion_tokens}; most other servers only know {@code max_tokens}.
 */
@FunctionalInterface
public interface OutputLimit {

	void apply(ToolCallingChatOptions.Builder<?> options, int maxTokens);

	/** The widely supported {@code max_tokens}. */
	static OutputLimit maxTokens() {
		return (options, maxTokens) -> options.maxTokens(maxTokens);
	}

	/** {@code max_completion_tokens}, for OpenAI models that no longer accept {@code max_tokens}. */
	static OutputLimit maxCompletionTokens() {
		return (options, maxTokens) -> {
			if (!(options instanceof OpenAiChatOptions.Builder openAi)) {
				throw new IllegalStateException("max_completion_tokens is an OpenAI option, but the chat model uses "
						+ options.getClass().getName());
			}
			openAi.maxCompletionTokens(maxTokens);
		};
	}
}
