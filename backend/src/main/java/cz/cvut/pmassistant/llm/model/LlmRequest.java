package cz.cvut.pmassistant.llm.model;

import java.util.List;
import java.util.Objects;

/**
 * A request to the model. The system prompt and tool list should stay constant between requests of a run (no
 * dates or ids inside) so providers that cache prompt prefixes can do so; volatile data belongs in messages.
 */
public record LlmRequest(String system, List<LlmMessage> messages, List<ToolSpec> tools, int maxTokens) {

	public LlmRequest {
		Objects.requireNonNull(system, "system");
		messages = List.copyOf(messages);
		tools = List.copyOf(tools);
		if (messages.isEmpty()) {
			throw new IllegalArgumentException("A request needs at least one message");
		}
		if (maxTokens <= 0) {
			throw new IllegalArgumentException("maxTokens must be positive");
		}
	}
}
