package cz.cvut.pmassistant.llm.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
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
import cz.cvut.pmassistant.llm.model.ToolSpec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RequestMapperTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final RequestMapper mapper = new RequestMapper(JSON);
	private final ChatModel chatModel = StubChatModel.answering(null);

	private static JsonNode json(String text) {
		return JSON.readTree(text);
	}

	private Prompt toPrompt(List<LlmMessage> messages, List<ToolSpec> tools) {
		return mapper.toPrompt(new LlmRequest("You triage issues.", messages, tools, 2000), chatModel,
				OutputLimit.maxTokens());
	}

	@Test
	void theSystemPromptIsTheFirstMessage() {
		Prompt prompt = toPrompt(List.of(LlmMessage.user("Triage issue 7")), List.of());

		assertThat(prompt.getInstructions()).extracting(Message::getMessageType)
				.containsExactly(MessageType.SYSTEM, MessageType.USER);
		assertThat(prompt.getInstructions().get(0).getText()).isEqualTo("You triage issues.");
		assertThat(prompt.getInstructions().get(1).getText()).isEqualTo("Triage issue 7");
	}

	@Test
	void aBlankSystemPromptIsLeftOut() {
		Prompt prompt = mapper.toPrompt(new LlmRequest(" ", List.of(LlmMessage.user("hi")), List.of(), 10), chatModel,
				OutputLimit.maxTokens());

		assertThat(prompt.getInstructions()).hasSize(1);
		assertThat(prompt.getInstructions().get(0)).isInstanceOf(UserMessage.class);
	}

	@Test
	void theOptionsKeepTheModelsDefaultsAndCarryTheOutputLimit() {
		ChatOptions options = toPrompt(List.of(LlmMessage.user("hi")), List.of()).getOptions();

		assertThat(options.getModel()).isEqualTo("stub-model");
		assertThat(options.getMaxTokens()).isEqualTo(2000);
	}

	@Test
	void toolsAreDeclaredWithTheirSchemaButNeverRunByTheModelClient() {
		ToolSpec spec = new ToolSpec("get_issue", "Reads an issue",
				json("{\"type\":\"object\",\"properties\":{\"number\":{\"type\":\"integer\"}}}"), false);

		ChatOptions options = toPrompt(List.of(LlmMessage.user("hi")), List.of(spec)).getOptions();

		List<ToolCallback> callbacks = ((ToolCallingChatOptions) options).getToolCallbacks();
		assertThat(callbacks).hasSize(1);
		assertThat(callbacks.get(0).getToolDefinition().name()).isEqualTo("get_issue");
		assertThat(callbacks.get(0).getToolDefinition().description()).isEqualTo("Reads an issue");
		assertThat(json(callbacks.get(0).getToolDefinition().inputSchema()))
				.isEqualTo(json("{\"type\":\"object\",\"properties\":{\"number\":{\"type\":\"integer\"}}}"));
		assertThatThrownBy(() -> callbacks.get(0).call("{}")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("agent loop");
	}

	@Test
	void anAssistantTurnKeepsItsTextAndItsToolCallsWithTheArgumentsAsJsonText() {
		LlmMessage assistant = LlmMessage.assistant(List.of(new ContentPart.Text("Let me look."),
				new ContentPart.ToolCall("c1", "get_issue", json("{\"number\":7}")),
				new ContentPart.ToolCall("c2", "list_labels", json("{}"))));

		Prompt prompt = toPrompt(List.of(LlmMessage.user("go"), assistant), List.of());

		AssistantMessage message = (AssistantMessage) prompt.getInstructions().get(2);
		assertThat(message.getText()).isEqualTo("Let me look.");
		assertThat(message.getToolCalls()).extracting(AssistantMessage.ToolCall::id).containsExactly("c1", "c2");
		assertThat(message.getToolCalls().get(0).name()).isEqualTo("get_issue");
		assertThat(message.getToolCalls().get(0).type()).isEqualTo("function");
		assertThat(json(message.getToolCalls().get(0).arguments())).isEqualTo(json("{\"number\":7}"));
	}

	@Test
	void everyToolResultAnswersItsCallByIdAndNameInOneMessage() {
		LlmMessage assistant = LlmMessage.assistant(List.of(
				new ContentPart.ToolCall("c1", "get_issue", json("{}")),
				new ContentPart.ToolCall("c2", "list_labels", json("{}"))));
		LlmMessage results = LlmMessage.toolResults(List.of(new ContentPart.ToolResult("c1", "issue text", false),
				new ContentPart.ToolResult("c2", "no such label", true)));

		Prompt prompt = toPrompt(List.of(LlmMessage.user("go"), assistant, results), List.of());

		ToolResponseMessage message = (ToolResponseMessage) prompt.getInstructions().get(3);
		assertThat(message.getResponses()).hasSize(2);
		assertThat(message.getResponses().get(0).id()).isEqualTo("c1");
		assertThat(message.getResponses().get(0).name()).isEqualTo("get_issue");
		assertThat(message.getResponses().get(0).responseData()).isEqualTo("issue text");
		assertThat(message.getResponses().get(1).name()).isEqualTo("list_labels");
		assertThat(message.getResponses().get(1).responseData()).as("an error is marked in the text")
				.isEqualTo("Error: no such label");
	}

	@Test
	void textThatComesWithToolResultsFollowsThemAsAUserMessage() {
		LlmMessage assistant = LlmMessage.assistant(List.of(new ContentPart.ToolCall("c1", "t", json("{}"))));
		LlmMessage mixed = new LlmMessage(Role.USER, List.of(new ContentPart.Text("Finish with submit."),
				new ContentPart.ToolResult("c1", "ok", false)));

		Prompt prompt = toPrompt(List.of(LlmMessage.user("go"), assistant, mixed), List.of());

		assertThat(prompt.getInstructions().get(3)).isInstanceOf(ToolResponseMessage.class);
		assertThat(prompt.getInstructions().get(4)).isInstanceOf(UserMessage.class);
		assertThat(prompt.getInstructions().get(4).getText()).isEqualTo("Finish with submit.");
	}

	@Test
	void aResultForACallWeNeverSawStillGoesOutWithAPlaceholderName() {
		LlmMessage results = LlmMessage.toolResults(List.of(new ContentPart.ToolResult("zz", "x", false)));

		Prompt prompt = toPrompt(List.of(LlmMessage.user("go"), results), List.of());

		assertThat(((ToolResponseMessage) prompt.getInstructions().get(2)).getResponses().get(0).name())
				.isEqualTo("tool");
	}

	@Test
	void opaqueBlocksOfAnotherProviderAreDropped() {
		LlmMessage assistant = LlmMessage.assistant(List.of(new ContentPart.Opaque("thinking", json("{\"s\":1}")),
				new ContentPart.Text("Answer.")));

		Prompt prompt = toPrompt(List.of(LlmMessage.user("go"), assistant), List.of());

		AssistantMessage message = (AssistantMessage) prompt.getInstructions().get(2);
		assertThat(message.getText()).isEqualTo("Answer.");
		assertThat(message.getToolCalls()).isEmpty();
	}

	@Test
	void aChatModelWithoutToolSupportIsRefusedWithAClearMessage() {
		ChatModel plain = new ChatModel() {
			@Override
			public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
				return null;
			}

			@Override
			public ChatOptions getDefaultOptions() {
				return ChatOptions.builder().model("plain").build();
			}
		};

		assertThatThrownBy(() -> mapper.toPrompt(
				new LlmRequest("s", List.of(LlmMessage.user("hi")), List.of(), 10), plain, OutputLimit.maxTokens()))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("does not support tool calling");
	}
}
