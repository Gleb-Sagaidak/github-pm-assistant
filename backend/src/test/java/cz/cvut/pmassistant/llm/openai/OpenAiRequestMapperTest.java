package cz.cvut.pmassistant.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import cz.cvut.pmassistant.llm.model.ContentPart;
import cz.cvut.pmassistant.llm.model.LlmMessage;
import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.ToolSpec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiRequestMapperTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final OpenAiRequestMapper mapper = new OpenAiRequestMapper(JSON);
	private final OpenAiCompatibleSettings settings = OpenAiCompatibleSettings.of("http://x/v1", "key", "gpt-test");

	private static JsonNode json(String text) {
		return JSON.readTree(text);
	}

	private JsonNode body(List<LlmMessage> messages, List<ToolSpec> tools) {
		return mapper.toBody(new LlmRequest("You are a triage agent.", messages, tools, 2000), settings);
	}

	@Test
	void theSystemPromptIsTheFirstMessageAndTheModelAndLimitAreSet() {
		JsonNode body = body(List.of(LlmMessage.user("Triage issue 7")), List.of());

		assertThat(body.path("model").asString()).isEqualTo("gpt-test");
		assertThat(body.path("max_tokens").asInt()).isEqualTo(2000);
		assertThat(body.path("messages").get(0)).isEqualTo(json("{\"role\":\"system\",\"content\":\"You are a triage agent.\"}"));
		assertThat(body.path("messages").get(1)).isEqualTo(json("{\"role\":\"user\",\"content\":\"Triage issue 7\"}"));
		assertThat(body.has("tools")).as("no tools, no field").isFalse();
	}

	@Test
	void theNameOfTheLimitFieldIsConfigurable() {
		OpenAiCompatibleSettings newer = new OpenAiCompatibleSettings("http://x/v1", "k", "m", "max_completion_tokens",
				Duration.ofSeconds(5), 0);

		JsonNode body = mapper.toBody(new LlmRequest("s", List.of(LlmMessage.user("hi")), List.of(), 777), newer);

		assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(777);
		assertThat(body.has("max_tokens")).isFalse();
	}

	@Test
	void aBlankSystemPromptIsLeftOut() {
		JsonNode body = mapper.toBody(new LlmRequest(" ", List.of(LlmMessage.user("hi")), List.of(), 10), settings);

		assertThat(body.path("messages")).hasSize(1);
		assertThat(body.path("messages").get(0).path("role").asString()).isEqualTo("user");
	}

	@Test
	void nothingIsSentThatAModelMightReject() {
		JsonNode body = body(List.of(LlmMessage.user("hi")),
				List.of(new ToolSpec("get_issue", "Reads an issue", json("{\"type\":\"object\"}"), false)));

		assertThat(body.has("temperature")).isFalse();
		assertThat(body.has("tool_choice")).isFalse();
		assertThat(body.has("top_p")).isFalse();
	}

	@Test
	void toolsBecomeFunctionsAndStrictIsOnlySentWhenAsked() {
		ToolSpec strict = new ToolSpec("submit", "Final answer", json("{\"type\":\"object\",\"properties\":{}}"), true);
		ToolSpec lax = new ToolSpec("get_issue", "Reads an issue", json("{\"type\":\"object\"}"), false);

		JsonNode tools = body(List.of(LlmMessage.user("hi")), List.of(lax, strict)).path("tools");

		assertThat(tools.get(0)).isEqualTo(json("""
				{"type":"function","function":{"name":"get_issue","description":"Reads an issue",
				 "parameters":{"type":"object"}}}"""));
		assertThat(tools.get(1)).isEqualTo(json("""
				{"type":"function","function":{"name":"submit","description":"Final answer",
				 "parameters":{"type":"object","properties":{}},"strict":true}}"""));
	}

	@Test
	void anAssistantTurnWithToolCallsCarriesTheArgumentsAsJsonText() {
		LlmMessage assistant = LlmMessage.assistant(List.of(
				new ContentPart.Text("Let me look."),
				new ContentPart.ToolCall("c1", "get_issue", json("{\"number\":7}")),
				new ContentPart.ToolCall("c2", "list_labels", json("{}"))));

		JsonNode message = body(List.of(LlmMessage.user("go"), assistant), List.of()).path("messages").get(2);

		assertThat(message.path("role").asString()).isEqualTo("assistant");
		assertThat(message.path("content").asString()).isEqualTo("Let me look.");
		assertThat(message.path("tool_calls")).hasSize(2);
		JsonNode first = message.path("tool_calls").get(0);
		assertThat(first.path("id").asString()).isEqualTo("c1");
		assertThat(first.path("type").asString()).isEqualTo("function");
		assertThat(first.path("function").path("name").asString()).isEqualTo("get_issue");
		assertThat(first.path("function").path("arguments").isString()).isTrue();
		assertThat(json(first.path("function").path("arguments").asString())).isEqualTo(json("{\"number\":7}"));
	}

	@Test
	void anAssistantTurnWithOnlyToolCallsHasNullContent() {
		LlmMessage assistant = LlmMessage.assistant(List.of(new ContentPart.ToolCall("c1", "t", json("{}"))));

		JsonNode message = body(List.of(LlmMessage.user("go"), assistant), List.of()).path("messages").get(2);

		assertThat(message.has("content")).isTrue();
		assertThat(message.path("content").isNull()).isTrue();
	}

	@Test
	void everyToolResultBecomesAToolMessageInOrder() {
		LlmMessage results = LlmMessage.toolResults(List.of(
				new ContentPart.ToolResult("c1", "issue text", false),
				new ContentPart.ToolResult("c2", "no such label", true)));

		JsonNode messages = body(List.of(LlmMessage.user("go"), results), List.of()).path("messages");

		assertThat(messages).hasSize(4);
		assertThat(messages.get(2)).isEqualTo(json("{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"issue text\"}"));
		assertThat(messages.get(3).path("tool_call_id").asString()).isEqualTo("c2");
		assertThat(messages.get(3).path("content").asString()).as("an error is marked in the text")
				.isEqualTo("Error: no such label");
	}

	@Test
	void textAfterToolResultsInOneMessageComesAsAUserMessageAfterThem() {
		LlmMessage mixed = new LlmMessage(cz.cvut.pmassistant.llm.model.Role.USER, List.of(
				new ContentPart.Text("Finish with submit."), new ContentPart.ToolResult("c1", "ok", false)));

		JsonNode messages = body(List.of(LlmMessage.user("go"), mixed), List.of()).path("messages");

		assertThat(messages.get(2).path("role").asString()).isEqualTo("tool");
		assertThat(messages.get(3)).isEqualTo(json("{\"role\":\"user\",\"content\":\"Finish with submit.\"}"));
	}

	@Test
	void opaqueBlocksOfAnotherProviderAreDropped() {
		LlmMessage assistant = LlmMessage.assistant(List.of(
				new ContentPart.Opaque("thinking", json("{\"signature\":\"abc\"}")), new ContentPart.Text("Answer.")));

		JsonNode message = body(List.of(LlmMessage.user("go"), assistant), List.of()).path("messages").get(2);

		assertThat(message).isEqualTo(json("{\"role\":\"assistant\",\"content\":\"Answer.\"}"));
	}

	@Test
	void anAssistantTurnWithNothingToSendStillHasContent() {
		LlmMessage assistant = LlmMessage.assistant(List.of(new ContentPart.Opaque("thinking", json("{}"))));

		JsonNode message = body(List.of(LlmMessage.user("go"), assistant), List.of()).path("messages").get(2);

		assertThat(message.path("content").asString()).isEmpty();
	}
}
