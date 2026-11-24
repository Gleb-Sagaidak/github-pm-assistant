package cz.cvut.pmassistant.llm.gateway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import cz.cvut.pmassistant.llm.model.ContentPart;
import cz.cvut.pmassistant.llm.model.LlmMessage;
import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.Role;

import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the provider-independent {@link LlmRequest} into a Spring AI {@link Prompt}.
 * <p>
 * Tools are declared to the model but never executed by Spring AI (see {@link DeclaredOnlyToolCallback}). The
 * request options are made from the model's own default options, because a provider's chat model accepts only its
 * own option class; that keeps the model, the key and the address that were configured. A result of a tool call
 * is an error for the model if it starts with {@code Error:}, since this message format has no flag for it. Parts
 * with no place in a Spring AI message (opaque blocks of another provider) are dropped.
 */
final class RequestMapper {

	private static final String ERROR_PREFIX = "Error: ";

	private final JsonMapper mapper;

	RequestMapper(JsonMapper mapper) {
		this.mapper = mapper;
	}

	Prompt toPrompt(LlmRequest request, ChatModel chatModel, OutputLimit limit) {
		List<Message> messages = new ArrayList<>();
		if (!request.system().isBlank()) {
			messages.add(new SystemMessage(request.system()));
		}
		// a tool result must name the tool; the domain only keeps the id of the call, so remember the names
		Map<String, String> toolNames = new HashMap<>();
		for (LlmMessage message : request.messages()) {
			if (message.role() == Role.ASSISTANT) {
				messages.add(assistantMessage(message, toolNames));
			}
			else {
				addUserMessages(messages, message, toolNames);
			}
		}
		return new Prompt(messages, options(request, chatModel, limit));
	}

	private ChatOptions options(LlmRequest request, ChatModel chatModel, OutputLimit limit) {
		if (!(chatModel.getDefaultOptions().mutate() instanceof ToolCallingChatOptions.Builder<?> builder)) {
			throw new IllegalStateException("The chat model " + chatModel.getClass().getName()
					+ " does not support tool calling");
		}
		List<ToolCallback> callbacks = request.tools().stream()
				.<ToolCallback>map(DeclaredOnlyToolCallback::new)
				.toList();
		builder.toolCallbacks(callbacks);
		limit.apply(builder, request.maxTokens());
		return builder.build();
	}

	private AssistantMessage assistantMessage(LlmMessage message, Map<String, String> toolNames) {
		List<AssistantMessage.ToolCall> calls = new ArrayList<>();
		for (ContentPart part : message.parts()) {
			if (part instanceof ContentPart.ToolCall call) {
				toolNames.put(call.id(), call.name());
				// the arguments travel as JSON text
				calls.add(new AssistantMessage.ToolCall(call.id(), "function", call.name(),
						mapper.writeValueAsString(call.arguments())));
			}
		}
		return AssistantMessage.builder().content(joinedText(message.parts())).toolCalls(calls).build();
	}

	private void addUserMessages(List<Message> messages, LlmMessage message, Map<String, String> toolNames) {
		List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
		for (ContentPart part : message.parts()) {
			if (part instanceof ContentPart.ToolResult result) {
				String name = toolNames.getOrDefault(result.toolCallId(), "tool");
				responses.add(new ToolResponseMessage.ToolResponse(result.toolCallId(), name,
						result.isError() ? ERROR_PREFIX + result.content() : result.content()));
			}
		}
		// the tool results must directly follow the assistant's calls, so they come before any text
		if (!responses.isEmpty()) {
			messages.add(ToolResponseMessage.builder().responses(responses).build());
		}
		String text = joinedText(message.parts());
		if (!text.isEmpty()) {
			messages.add(new UserMessage(text));
		}
	}

	private static String joinedText(List<ContentPart> parts) {
		return parts.stream()
				.filter(ContentPart.Text.class::isInstance)
				.map(part -> ((ContentPart.Text) part).text())
				.collect(Collectors.joining("\n\n"));
	}
}
