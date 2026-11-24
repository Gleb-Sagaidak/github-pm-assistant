package cz.cvut.pmassistant.llm.model;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;

public record LlmResponse(List<ContentPart> parts, StopReason stopReason, Usage usage) {

	public LlmResponse {
		parts = List.copyOf(parts);
		Objects.requireNonNull(stopReason, "stopReason");
		Objects.requireNonNull(usage, "usage");
	}

	/** The tool calls in this response, in order; several means the model asked for parallel calls. */
	public List<ContentPart.ToolCall> toolCalls() {
		return parts.stream()
				.filter(ContentPart.ToolCall.class::isInstance)
				.map(ContentPart.ToolCall.class::cast)
				.toList();
	}

	/** All text parts joined; empty if there is none. */
	public String text() {
		return parts.stream()
				.filter(ContentPart.Text.class::isInstance)
				.map(part -> ((ContentPart.Text) part).text())
				.collect(Collectors.joining("\n"));
	}

	public static LlmResponse ofText(String text, Usage usage) {
		return new LlmResponse(List.of(new ContentPart.Text(text)), StopReason.END_TURN, usage);
	}

	public static LlmResponse ofToolCalls(Usage usage, ContentPart.ToolCall... calls) {
		return new LlmResponse(List.of(calls), StopReason.TOOL_USE, usage);
	}

	public static LlmResponse ofToolCall(String id, String name, JsonNode arguments, Usage usage) {
		return ofToolCalls(usage, new ContentPart.ToolCall(id, name, arguments));
	}

	public static LlmResponse ofRefusal(String text, Usage usage) {
		return new LlmResponse(List.of(new ContentPart.Text(text)), StopReason.REFUSAL, usage);
	}
}
