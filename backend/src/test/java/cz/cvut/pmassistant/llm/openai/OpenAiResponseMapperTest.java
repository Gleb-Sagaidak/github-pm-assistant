package cz.cvut.pmassistant.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import cz.cvut.pmassistant.llm.LlmUnavailableException;
import cz.cvut.pmassistant.llm.model.ContentPart;
import cz.cvut.pmassistant.llm.model.LlmResponse;
import cz.cvut.pmassistant.llm.model.StopReason;
import cz.cvut.pmassistant.llm.model.Usage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

class OpenAiResponseMapperTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final OpenAiResponseMapper mapper = new OpenAiResponseMapper(JSON);

	private LlmResponse map(String body) {
		return mapper.toResponse(JSON.readTree(body));
	}

	private static String choice(String message, String finishReason) {
		return "{\"choices\":[{\"message\":" + message + ",\"finish_reason\":" + finishReason + "}]}";
	}

	@Test
	void aTextAnswerWithUsageAndCachedTokens() {
		LlmResponse response = map(OpenAiFixtures.read("chat-text.json"));

		assertThat(response.text()).isEqualTo("It looks like a bug in the login flow.");
		assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(response.usage()).isEqualTo(new Usage(120, 12, 100));
		assertThat(response.toolCalls()).isEmpty();
	}

	@Test
	void aToolCallWithArgumentsGivenAsJsonText() {
		LlmResponse response = map(OpenAiFixtures.read("chat-tool-call.json"));

		assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
		assertThat(response.toolCalls()).singleElement().satisfies(call -> {
			assertThat(call.id()).isEqualTo("call_abc123");
			assertThat(call.name()).isEqualTo("get_issue");
			assertThat(call.arguments()).isEqualTo(JSON.readTree("{\"number\":7,\"repo\":\"cvut-team/pm-assistant\"}"));
		});
		assertThat(response.text()).isEmpty();
		assertThat(response.usage()).isEqualTo(new Usage(200, 30, 0));
	}

	@Test
	void parallelToolCallsKeepTheirOrderAndTheAccompanyingText() {
		LlmResponse response = map(OpenAiFixtures.read("chat-parallel-tool-calls.json"));

		assertThat(response.toolCalls()).extracting(ContentPart.ToolCall::name).containsExactly("get_issue", "list_labels");
		assertThat(response.text()).isEqualTo("I will read the issue and the labels.");
		assertThat(response.parts().get(0)).isInstanceOf(ContentPart.Text.class);
	}

	@Test
	void aServerThatSendsNoCallIdGetsOneGenerated() {
		LlmResponse response = map(choice("""
				{"role":"assistant","content":"","tool_calls":[
				  {"type":"function","function":{"name":"get_issue","arguments":"{}"}},
				  {"type":"function","function":{"name":"list_labels","arguments":"{}"}}]}""", "\"tool_calls\""));

		assertThat(response.toolCalls()).extracting(ContentPart.ToolCall::id).containsExactly("call_0", "call_1");
	}

	@Test
	void argumentsThatAreNotJsonAreKeptAsTextSoTheModelCanBeToldOff() {
		LlmResponse response = map(choice("""
				{"role":"assistant","content":null,"tool_calls":[
				  {"id":"c1","type":"function","function":{"name":"get_issue","arguments":"{number: 7"}}]}""",
				"\"tool_calls\""));

		JsonNode arguments = response.toolCalls().get(0).arguments();
		assertThat(arguments).isEqualTo(StringNode.valueOf("{number: 7"));
		assertThat(arguments.isObject()).isFalse();
	}

	@Test
	void emptyArgumentsMeanNoArguments() {
		LlmResponse blank = map(choice("""
				{"content":null,"tool_calls":[{"id":"c1","function":{"name":"list_labels","arguments":""}}]}""",
				"\"tool_calls\""));
		LlmResponse missing = map(choice("""
				{"content":null,"tool_calls":[{"id":"c1","function":{"name":"list_labels"}}]}""", "\"tool_calls\""));

		assertThat(blank.toolCalls().get(0).arguments()).isEqualTo(JSON.createObjectNode());
		assertThat(missing.toolCalls().get(0).arguments()).isEqualTo(JSON.createObjectNode());
	}

	@Test
	void argumentsAlreadyGivenAsAnObjectAreAccepted() {
		LlmResponse response = map(choice("""
				{"content":null,"tool_calls":[{"id":"c1","function":{"name":"get_issue","arguments":{"number":7}}}]}""",
				"\"tool_calls\""));

		assertThat(response.toolCalls().get(0).arguments()).isEqualTo(JSON.readTree("{\"number\":7}"));
	}

	@Test
	void aCallWithoutANameIsIgnored() {
		LlmResponse response = map(choice("""
				{"content":"ok","tool_calls":[{"id":"c1","function":{"arguments":"{}"}}]}""", "\"stop\""));

		assertThat(response.toolCalls()).isEmpty();
		assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void stopReasonsAreNormalized() {
		assertThat(map(choice("{\"content\":\"x\"}", "\"stop\"")).stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(map(choice("{\"content\":\"x\"}", "\"length\"")).stopReason()).isEqualTo(StopReason.MAX_TOKENS);
		assertThat(map(choice("{\"content\":\"x\"}", "\"content_filter\"")).stopReason()).isEqualTo(StopReason.REFUSAL);
		assertThat(map(choice("{\"content\":\"x\"}", "\"something_new\"")).stopReason()).isEqualTo(StopReason.OTHER);
		assertThat(map(choice("{\"content\":\"x\"}", "null")).stopReason()).isEqualTo(StopReason.OTHER);
	}

	@Test
	void toolCallsMakeItToolUseEvenIfTheServerSaysStop() {
		LlmResponse response = map(choice("""
				{"content":null,"tool_calls":[{"id":"c1","function":{"name":"get_issue","arguments":"{}"}}]}""",
				"\"stop\""));

		assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
	}

	@Test
	void theOlderFunctionCallReasonCountsAsToolUse() {
		assertThat(map(choice("{\"content\":null}", "\"function_call\"")).stopReason()).isEqualTo(StopReason.TOOL_USE);
	}

	@Test
	void aRefusalFieldMakesItARefusalWhateverTheFinishReasonIs() {
		LlmResponse response = map(choice("{\"content\":null,\"refusal\":\"I can't help with that.\"}", "\"stop\""));

		assertThat(response.stopReason()).isEqualTo(StopReason.REFUSAL);
		assertThat(response.text()).isEqualTo("I can't help with that.");
	}

	@Test
	void aTruncatedAnswerWinsOverItsPartialToolCalls() {
		LlmResponse response = map(choice("""
				{"content":null,"tool_calls":[{"id":"c1","function":{"name":"get_issue","arguments":"{\\"num"}}]}""",
				"\"length\""));

		assertThat(response.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
	}

	@Test
	void usageIsZeroWhenTheServerReportsNone() {
		assertThat(map(choice("{\"content\":\"x\"}", "\"stop\"")).usage()).isEqualTo(Usage.ZERO);
	}

	@Test
	void cachedTokensAreReadFromOtherServersFieldToo() {
		LlmResponse response = map("""
				{"choices":[{"message":{"content":"x"},"finish_reason":"stop"}],
				 "usage":{"prompt_tokens":50,"completion_tokens":5,"prompt_cache_hit_tokens":40}}""");

		assertThat(response.usage()).isEqualTo(new Usage(50, 5, 40));
	}

	@Test
	void anAnswerWithoutChoicesIsAnError() {
		assertThatThrownBy(() -> map("{\"choices\":[]}")).isInstanceOf(LlmUnavailableException.class);
		assertThatThrownBy(() -> map("{}")).isInstanceOf(LlmUnavailableException.class)
				.satisfies(e -> assertThat(((LlmUnavailableException) e).reason())
						.isEqualTo(LlmUnavailableException.Reason.OTHER));
	}
}
