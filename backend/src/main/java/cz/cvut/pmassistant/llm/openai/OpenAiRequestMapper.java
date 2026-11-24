package cz.cvut.pmassistant.llm.openai;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import cz.cvut.pmassistant.llm.model.ContentPart;
import cz.cvut.pmassistant.llm.model.LlmMessage;
import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.Role;
import cz.cvut.pmassistant.llm.model.ToolSpec;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns the provider-independent {@link LlmRequest} into the body of a {@code chat/completions} request.
 * <p>
 * Differences between the two models of a conversation, handled here: the system prompt is a message; a result of
 * a tool call is a message of its own with the {@code tool} role (one per call), while the domain keeps all
 * results of a turn together in one user message; a failed tool result has no error flag in this protocol, so it
 * is marked in the text. Parts the protocol has no place for (opaque blocks of another provider) are dropped.
 * <p>
 * Nothing is sent that a model might reject: no temperature, no forced {@code tool_choice}.
 */
final class OpenAiRequestMapper {

	private static final String ERROR_PREFIX = "Error: ";

	private final JsonMapper mapper;

	OpenAiRequestMapper(JsonMapper mapper) {
		this.mapper = mapper;
	}

	ObjectNode toBody(LlmRequest request, OpenAiCompatibleSettings settings) {
		ObjectNode body = mapper.createObjectNode();
		body.put("model", settings.model());
		ArrayNode messages = body.putArray("messages");
		if (!request.system().isBlank()) {
			messages.addObject().put("role", "system").put("content", request.system());
		}
		for (LlmMessage message : request.messages()) {
			addMessage(messages, message);
		}
		if (!request.tools().isEmpty()) {
			ArrayNode tools = body.putArray("tools");
			request.tools().forEach(spec -> tools.add(toTool(spec)));
		}
		body.put(settings.maxTokensParameter(), request.maxTokens());
		return body;
	}

	private ObjectNode toTool(ToolSpec spec) {
		ObjectNode tool = mapper.createObjectNode();
		tool.put("type", "function");
		ObjectNode function = tool.putObject("function");
		function.put("name", spec.name());
		function.put("description", spec.description());
		function.set("parameters", spec.inputSchema());
		if (spec.strict()) {
			function.put("strict", true);
		}
		return tool;
	}

	private void addMessage(ArrayNode messages, LlmMessage message) {
		if (message.role() == Role.ASSISTANT) {
			messages.add(assistantMessage(message));
			return;
		}
		// A user message: the tool results first (they must directly follow the assistant's calls), then text.
		for (ContentPart part : message.parts()) {
			if (part instanceof ContentPart.ToolResult result) {
				ObjectNode tool = messages.addObject();
				tool.put("role", "tool");
				tool.put("tool_call_id", result.toolCallId());
				tool.put("content", result.isError() ? ERROR_PREFIX + result.content() : result.content());
			}
		}
		String text = joinedText(message.parts());
		if (!text.isEmpty()) {
			messages.addObject().put("role", "user").put("content", text);
		}
	}

	private ObjectNode assistantMessage(LlmMessage message) {
		ObjectNode assistant = mapper.createObjectNode();
		assistant.put("role", "assistant");
		String text = joinedText(message.parts());
		List<ContentPart.ToolCall> calls = new ArrayList<>();
		for (ContentPart part : message.parts()) {
			if (part instanceof ContentPart.ToolCall call) {
				calls.add(call);
			}
		}
		if (text.isEmpty() && !calls.isEmpty()) {
			assistant.putNull("content");
		}
		else {
			assistant.put("content", text);
		}
		if (!calls.isEmpty()) {
			ArrayNode toolCalls = assistant.putArray("tool_calls");
			for (ContentPart.ToolCall call : calls) {
				ObjectNode entry = toolCalls.addObject();
				entry.put("id", call.id());
				entry.put("type", "function");
				ObjectNode function = entry.putObject("function");
				function.put("name", call.name());
				// the protocol carries the arguments as a JSON text, not as an object
				function.put("arguments", mapper.writeValueAsString(call.arguments()));
			}
		}
		return assistant;
	}

	private static String joinedText(List<ContentPart> parts) {
		return parts.stream()
				.filter(ContentPart.Text.class::isInstance)
				.map(part -> ((ContentPart.Text) part).text())
				.collect(Collectors.joining("\n\n"));
	}
}
