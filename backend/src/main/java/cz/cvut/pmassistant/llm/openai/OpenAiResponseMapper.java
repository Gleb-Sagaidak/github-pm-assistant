package cz.cvut.pmassistant.llm.openai;

import java.util.ArrayList;
import java.util.List;

import cz.cvut.pmassistant.llm.LlmUnavailableException;
import cz.cvut.pmassistant.llm.model.ContentPart;
import cz.cvut.pmassistant.llm.model.LlmResponse;
import cz.cvut.pmassistant.llm.model.StopReason;
import cz.cvut.pmassistant.llm.model.Usage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

/**
 * Turns a {@code chat/completions} answer into the provider-independent {@link LlmResponse}, normalizing the
 * stop reason. It is forgiving about what servers other than OpenAI leave out (call ids, token counts) or send in
 * another shape (arguments as an object, empty arguments).
 */
final class OpenAiResponseMapper {

	private final JsonMapper mapper;

	OpenAiResponseMapper(JsonMapper mapper) {
		this.mapper = mapper;
	}

	LlmResponse toResponse(JsonNode body) {
		JsonNode choices = body.path("choices");
		if (!choices.isArray() || choices.isEmpty()) {
			throw new LlmUnavailableException(LlmUnavailableException.Reason.OTHER,
					"The answer contains no choices");
		}
		JsonNode choice = choices.get(0);
		JsonNode message = choice.path("message");

		List<ContentPart> parts = new ArrayList<>();
		String content = text(message.path("content"));
		if (content != null && !content.isEmpty()) {
			parts.add(new ContentPart.Text(content));
		}
		String refusal = text(message.path("refusal"));
		if (refusal != null && !refusal.isEmpty()) {
			parts.add(new ContentPart.Text(refusal));
		}
		int calls = addToolCalls(message.path("tool_calls"), parts);

		StopReason stopReason = stopReason(text(choice.path("finish_reason")), calls > 0,
				refusal != null && !refusal.isEmpty());
		return new LlmResponse(parts, stopReason, usage(body.path("usage")));
	}

	private int addToolCalls(JsonNode toolCalls, List<ContentPart> parts) {
		if (!toolCalls.isArray()) {
			return 0;
		}
		int index = 0;
		for (JsonNode call : toolCalls) {
			JsonNode function = call.path("function");
			String name = text(function.path("name"));
			if (name == null || name.isBlank()) {
				continue;
			}
			// some local servers send no id; the loop only needs one that is unique within the answer
			String id = text(call.path("id"));
			if (id == null || id.isBlank()) {
				id = "call_" + index;
			}
			parts.add(new ContentPart.ToolCall(id, name, arguments(function.path("arguments"))));
			index++;
		}
		return index;
	}

	/**
	 * The arguments are a JSON text in this protocol. If the model wrote something that is not JSON, the raw text is
	 * kept as a string, which fails schema validation and so reaches the model as an error it can correct.
	 */
	private JsonNode arguments(JsonNode raw) {
		if (raw.isObject() || raw.isArray()) {
			return raw;
		}
		String text = text(raw);
		if (text == null || text.isBlank()) {
			return mapper.createObjectNode();
		}
		try {
			return mapper.readTree(text);
		}
		catch (JacksonException e) {
			return StringNode.valueOf(text);
		}
	}

	private static StopReason stopReason(String finishReason, boolean hasToolCalls, boolean refused) {
		if (refused || "content_filter".equals(finishReason)) {
			return StopReason.REFUSAL;
		}
		if ("length".equals(finishReason)) {
			return StopReason.MAX_TOKENS;
		}
		if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason) || hasToolCalls) {
			return StopReason.TOOL_USE;
		}
		return "stop".equals(finishReason) ? StopReason.END_TURN : StopReason.OTHER;
	}

	private static Usage usage(JsonNode usage) {
		if (!usage.isObject()) {
			return Usage.ZERO;
		}
		int cached = usage.path("prompt_tokens_details").path("cached_tokens").asInt(
				usage.path("prompt_cache_hit_tokens").asInt(0));
		return new Usage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0), cached);
	}

	/** The text of a string node, or null for a missing or null node. */
	private static String text(JsonNode node) {
		return node.isString() ? node.asString() : null;
	}
}
