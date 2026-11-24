package cz.cvut.pmassistant.llm.model;

import java.util.List;
import java.util.Objects;

public record LlmMessage(Role role, List<ContentPart> parts) {

	public LlmMessage {
		Objects.requireNonNull(role, "role");
		parts = List.copyOf(parts);
		if (parts.isEmpty()) {
			throw new IllegalArgumentException("A message needs at least one part");
		}
	}

	public static LlmMessage user(String text) {
		return new LlmMessage(Role.USER, List.of(new ContentPart.Text(text)));
	}

	public static LlmMessage assistant(List<ContentPart> parts) {
		return new LlmMessage(Role.ASSISTANT, parts);
	}

	/** All results of one assistant turn go into ONE message, so parallel tool calls stay parallel. */
	public static LlmMessage toolResults(List<ContentPart.ToolResult> results) {
		return new LlmMessage(Role.USER, List.copyOf(results));
	}
}
