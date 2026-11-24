package cz.cvut.pmassistant.llm.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import cz.cvut.pmassistant.llm.LlmUnavailableException;
import cz.cvut.pmassistant.llm.model.ContentPart;
import cz.cvut.pmassistant.llm.model.LlmResponse;
import cz.cvut.pmassistant.llm.model.StopReason;
import cz.cvut.pmassistant.llm.model.Usage;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

class ResponseMapperTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final ResponseMapper mapper = new ResponseMapper(JSON);

	private static ChatResponse response(AssistantMessage message, String finishReason, DefaultUsage usage) {
		return new ChatResponse(
				List.of(new Generation(message, ChatGenerationMetadata.builder().finishReason(finishReason).build())),
				ChatResponseMetadata.builder().usage(usage).build());
	}

	private static AssistantMessage callMessage(AssistantMessage.ToolCall... calls) {
		return AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
	}

	@Test
	void aTextAnswerWithUsageAndCachedTokens() {
		ChatResponse chat = response(new AssistantMessage("It looks like a bug."), "STOP",
				new DefaultUsage(120, 12, 132, null, 100L, null));

		LlmResponse result = mapper.toResponse(chat);

		assertThat(result.text()).isEqualTo("It looks like a bug.");
		assertThat(result.stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(result.usage()).isEqualTo(new Usage(120, 12, 100));
		assertThat(result.toolCalls()).isEmpty();
	}

	@Test
	void aToolCallWithArgumentsGivenAsJsonText() {
		ChatResponse chat = response(callMessage(
				new AssistantMessage.ToolCall("call_abc", "function", "get_issue", "{\"number\": 7}")), "TOOL_CALLS",
				new DefaultUsage(200, 30));

		LlmResponse result = mapper.toResponse(chat);

		assertThat(result.stopReason()).isEqualTo(StopReason.TOOL_USE);
		assertThat(result.toolCalls()).singleElement().satisfies(call -> {
			assertThat(call.id()).isEqualTo("call_abc");
			assertThat(call.name()).isEqualTo("get_issue");
			assertThat(call.arguments()).isEqualTo(JSON.readTree("{\"number\":7}"));
		});
		assertThat(result.text()).isEmpty();
	}

	@Test
	void parallelToolCallsKeepTheirOrderAndTheAccompanyingText() {
		AssistantMessage message = AssistantMessage.builder().content("I will read both.").toolCalls(List.of(
				new AssistantMessage.ToolCall("c1", "function", "get_issue", "{}"),
				new AssistantMessage.ToolCall("c2", "function", "list_labels", "{}"))).build();

		LlmResponse result = mapper.toResponse(response(message, "TOOL_CALLS", new DefaultUsage(1, 1)));

		assertThat(result.toolCalls()).extracting(ContentPart.ToolCall::name).containsExactly("get_issue", "list_labels");
		assertThat(result.parts().get(0)).isEqualTo(new ContentPart.Text("I will read both."));
	}

	@Test
	void aServerThatSendsNoCallIdGetsOneGenerated() {
		ChatResponse chat = response(callMessage(
				new AssistantMessage.ToolCall(null, "function", "get_issue", "{}"),
				new AssistantMessage.ToolCall("", "function", "list_labels", "{}")), "TOOL_CALLS",
				new DefaultUsage(1, 1));

		assertThat(mapper.toResponse(chat).toolCalls()).extracting(ContentPart.ToolCall::id)
				.containsExactly("call_0", "call_1");
	}

	@Test
	void argumentsThatAreNotJsonAreKeptAsTextSoTheModelCanBeToldOff() {
		ChatResponse chat = response(callMessage(
				new AssistantMessage.ToolCall("c1", "function", "get_issue", "{number: 7")), "TOOL_CALLS",
				new DefaultUsage(1, 1));

		assertThat(mapper.toResponse(chat).toolCalls().get(0).arguments()).isEqualTo(StringNode.valueOf("{number: 7"));
	}

	@Test
	void emptyArgumentsMeanNoArguments() {
		ChatResponse chat = response(callMessage(
				new AssistantMessage.ToolCall("c1", "function", "list_labels", ""),
				new AssistantMessage.ToolCall("c2", "function", "list_labels", null)), "TOOL_CALLS",
				new DefaultUsage(1, 1));

		assertThat(mapper.toResponse(chat).toolCalls()).extracting(ContentPart.ToolCall::arguments)
				.containsExactly(JSON.createObjectNode(), JSON.createObjectNode());
	}

	@Test
	void aCallWithoutANameIsIgnored() {
		ChatResponse chat = response(callMessage(new AssistantMessage.ToolCall("c1", "function", " ", "{}")), "STOP",
				new DefaultUsage(1, 1));

		LlmResponse result = mapper.toResponse(chat);

		assertThat(result.toolCalls()).isEmpty();
		assertThat(result.stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void stopReasonsAreNormalized() {
		AssistantMessage text = new AssistantMessage("x");
		DefaultUsage usage = new DefaultUsage(1, 1);

		assertThat(mapper.toResponse(response(text, "STOP", usage)).stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(mapper.toResponse(response(text, "stop", usage)).stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(mapper.toResponse(response(text, "LENGTH", usage)).stopReason()).isEqualTo(StopReason.MAX_TOKENS);
		assertThat(mapper.toResponse(response(text, "CONTENT_FILTER", usage)).stopReason())
				.isEqualTo(StopReason.REFUSAL);
		assertThat(mapper.toResponse(response(text, "FUNCTION_CALL", usage)).stopReason())
				.isEqualTo(StopReason.TOOL_USE);
		assertThat(mapper.toResponse(response(text, "SOMETHING_NEW", usage)).stopReason()).isEqualTo(StopReason.OTHER);
		assertThat(mapper.toResponse(response(text, null, usage)).stopReason()).isEqualTo(StopReason.OTHER);
	}

	@Test
	void toolCallsMakeItToolUseEvenIfTheServerSaysStop() {
		ChatResponse chat = response(callMessage(new AssistantMessage.ToolCall("c1", "function", "get_issue", "{}")),
				"STOP", new DefaultUsage(1, 1));

		assertThat(mapper.toResponse(chat).stopReason()).isEqualTo(StopReason.TOOL_USE);
	}

	@Test
	void aRefusalInTheMetadataMakesItARefusalWhateverTheFinishReasonIs() {
		AssistantMessage refusal = AssistantMessage.builder().content("")
				.properties(Map.of("refusal", "I can't help with that.")).build();

		LlmResponse result = mapper.toResponse(response(refusal, "STOP", new DefaultUsage(1, 1)));

		assertThat(result.stopReason()).isEqualTo(StopReason.REFUSAL);
		assertThat(result.text()).isEqualTo("I can't help with that.");
	}

	@Test
	void aTruncatedAnswerWinsOverItsPartialToolCalls() {
		ChatResponse chat = response(callMessage(new AssistantMessage.ToolCall("c1", "function", "get_issue", "{\"num")),
				"LENGTH", new DefaultUsage(1, 1));

		assertThat(mapper.toResponse(chat).stopReason()).isEqualTo(StopReason.MAX_TOKENS);
	}

	@Test
	void missingUsageNumbersCountAsZero() {
		ChatResponse chat = response(new AssistantMessage("x"), "STOP", new DefaultUsage(null, null));

		assertThat(mapper.toResponse(chat).usage()).isEqualTo(Usage.ZERO);
	}

	@Test
	void anAnswerWithoutAResultIsAnError() {
		assertThatThrownBy(() -> mapper.toResponse(new ChatResponse(List.of())))
				.isInstanceOf(LlmUnavailableException.class);
		assertThatThrownBy(() -> mapper.toResponse(null)).isInstanceOf(LlmUnavailableException.class);
	}
}
