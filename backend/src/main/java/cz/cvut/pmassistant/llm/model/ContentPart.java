package cz.cvut.pmassistant.llm.model;

import java.util.Objects;

import tools.jackson.databind.JsonNode;

/**
 * One piece of a message. The domain only knows what the agent loop needs; everything provider-specific is
 * translated by the adapter at the edge (no provider classes appear in this model).
 */
public sealed interface ContentPart {

	/** Plain text. */
	record Text(String text) implements ContentPart {

		public Text {
			Objects.requireNonNull(text, "text");
		}
	}

	/** The model asks for a tool to be run. {@code id} links the call to its {@link ToolResult}. */
	record ToolCall(String id, String name, JsonNode arguments) implements ContentPart {

		public ToolCall {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(arguments, "arguments");
		}
	}

	/** The outcome of a tool call, sent back to the model. {@code isError} tells the model it failed. */
	record ToolResult(String toolCallId, String content, boolean isError) implements ContentPart {

		public ToolResult {
			Objects.requireNonNull(toolCallId, "toolCallId");
			Objects.requireNonNull(content, "content");
		}
	}

	/**
	 * A block the domain does not need but the provider requires to be sent back unchanged in the next request
	 * (for example a model's reasoning block). The loop stores and returns it without looking inside.
	 *
	 * @param type    provider's name for the block kind
	 * @param payload the block exactly as the provider gave it
	 */
	record Opaque(String type, JsonNode payload) implements ContentPart {

		public Opaque {
			Objects.requireNonNull(type, "type");
			Objects.requireNonNull(payload, "payload");
		}
	}
}
