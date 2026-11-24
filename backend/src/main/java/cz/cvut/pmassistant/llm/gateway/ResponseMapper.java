package cz.cvut.pmassistant.llm.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import cz.cvut.pmassistant.llm.LlmUnavailableException;
import cz.cvut.pmassistant.llm.model.ContentPart;
import cz.cvut.pmassistant.llm.model.LlmResponse;
import cz.cvut.pmassistant.llm.model.StopReason;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

/**
 * Turns a Spring AI {@link ChatResponse} into the provider-independent {@link LlmResponse}, normalizing the stop
 * reason. It is forgiving about what servers other than OpenAI leave out (call ids, token counts).
 */
final class ResponseMapper {

	private final JsonMapper mapper;

	ResponseMapper(JsonMapper mapper) {
		this.mapper = mapper;
	}

	LlmResponse toResponse(ChatResponse response) {
		Generation generation = response == null ? null : response.getResult();
		if (generation == null || generation.getOutput() == null) {
			throw new LlmUnavailableException(LlmUnavailableException.Reason.OTHER, "The answer contains no result");
		}
		AssistantMessage output = generation.getOutput();

		List<ContentPart> parts = new ArrayList<>();
		if (output.getText() != null && !output.getText().isEmpty()) {
			parts.add(new ContentPart.Text(output.getText()));
		}
		Object refusal = output.getMetadata().get("refusal");
		boolean refused = refusal instanceof String text && !text.isEmpty();
		if (refused) {
			parts.add(new ContentPart.Text((String) refusal));
		}
		int calls = addToolCalls(output, parts);

		String finishReason = generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason();
		return new LlmResponse(parts, stopReason(finishReason, calls > 0, refused), usage(response));
	}

	private int addToolCalls(AssistantMessage output, List<ContentPart> parts) {
		int index = 0;
		for (AssistantMessage.ToolCall call : output.getToolCalls()) {
			if (call.name() == null || call.name().isBlank()) {
				continue;
			}
			// some servers send no id; the loop only needs one that is unique within the answer
			String id = call.id() == null || call.id().isBlank() ? "call_" + index : call.id();
			parts.add(new ContentPart.ToolCall(id, call.name(), arguments(call.arguments())));
			index++;
		}
		return index;
	}

	/**
	 * The arguments arrive as JSON text. If the model wrote something that is not JSON, the raw text is kept as a
	 * string, which fails schema validation and so reaches the model as an error it can correct.
	 */
	private JsonNode arguments(String text) {
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
		String reason = finishReason == null ? "" : finishReason.toUpperCase(Locale.ROOT);
		if (refused || reason.equals("CONTENT_FILTER")) {
			return StopReason.REFUSAL;
		}
		if (reason.equals("LENGTH")) {
			return StopReason.MAX_TOKENS;
		}
		if (reason.equals("TOOL_CALLS") || reason.equals("FUNCTION_CALL") || hasToolCalls) {
			return StopReason.TOOL_USE;
		}
		return reason.equals("STOP") ? StopReason.END_TURN : StopReason.OTHER;
	}

	private static cz.cvut.pmassistant.llm.model.Usage usage(ChatResponse response) {
		Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
		if (usage == null) {
			return cz.cvut.pmassistant.llm.model.Usage.ZERO;
		}
		Long cached = usage.getCacheReadInputTokens();
		return new cz.cvut.pmassistant.llm.model.Usage(zeroIfNull(usage.getPromptTokens()),
				zeroIfNull(usage.getCompletionTokens()), cached == null ? 0 : (int) Math.min(cached, Integer.MAX_VALUE));
	}

	private static int zeroIfNull(Integer value) {
		return value == null ? 0 : value;
	}
}
