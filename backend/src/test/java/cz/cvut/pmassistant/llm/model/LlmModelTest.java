package cz.cvut.pmassistant.llm.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class LlmModelTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static JsonNode json(String text) {
		return JSON.readTree(text);
	}

	@Test
	void aMessageNeedsPartsAndKeepsAnImmutableCopy() {
		List<ContentPart> parts = new ArrayList<>(List.of(new ContentPart.Text("hi")));
		LlmMessage message = LlmMessage.assistant(parts);
		parts.add(new ContentPart.Text("added later"));

		assertThat(message.parts()).hasSize(1);
		assertThatThrownBy(() -> message.parts().add(new ContentPart.Text("x")))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> new LlmMessage(Role.USER, List.of())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void userMessagesCarryText() {
		LlmMessage message = LlmMessage.user("Triage issue 7");

		assertThat(message.role()).isEqualTo(Role.USER);
		assertThat(message.parts()).containsExactly(new ContentPart.Text("Triage issue 7"));
	}

	@Test
	void toolResultsShareOneUserMessage() {
		LlmMessage message = LlmMessage.toolResults(List.of(
				new ContentPart.ToolResult("c1", "first", false),
				new ContentPart.ToolResult("c2", "boom", true)));

		assertThat(message.role()).isEqualTo(Role.USER);
		assertThat(message.parts()).hasSize(2);
	}

	@Test
	void partsRejectMissingValues() {
		assertThatThrownBy(() -> new ContentPart.Text(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ContentPart.ToolCall(null, "t", json("{}"))).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ContentPart.ToolResult("c", null, false)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ContentPart.Opaque("thinking", null)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void anOpaquePartKeepsItsPayloadUntouched() {
		JsonNode payload = json("{\"signature\":\"abc\",\"nested\":{\"n\":[1,2,3]}}");

		ContentPart.Opaque opaque = new ContentPart.Opaque("thinking", payload);

		assertThat(opaque.payload()).isEqualTo(payload);
		assertThat(opaque.type()).isEqualTo("thinking");
	}

	@Test
	void toolSpecsValidateNameAndSchema() {
		JsonNode schema = json("{\"type\":\"object\",\"properties\":{}}");

		assertThat(new ToolSpec("get_issue", "Reads an issue", schema, true).name()).isEqualTo("get_issue");
		assertThatThrownBy(() -> new ToolSpec("has space", "d", schema, false))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ToolSpec("", "d", schema, false)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ToolSpec("x".repeat(65), "d", schema, false))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ToolSpec("t", "d", json("[1]"), false))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requestsNeedMessagesAndAPositiveLimitAndAreImmutable() {
		List<LlmMessage> messages = new ArrayList<>(List.of(LlmMessage.user("hi")));
		LlmRequest request = new LlmRequest("system", messages, List.of(), 1000);
		messages.add(LlmMessage.user("later"));

		assertThat(request.messages()).hasSize(1);
		assertThatThrownBy(() -> new LlmRequest("s", List.of(), List.of(), 10))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LlmRequest("s", List.of(LlmMessage.user("x")), List.of(), 0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void usageAddsUpAndCountsInputPlusOutputAgainstTheLimit() {
		Usage total = new Usage(100, 20, 60).plus(new Usage(150, 30, 100));

		assertThat(total).isEqualTo(new Usage(250, 50, 160));
		assertThat(total.totalTokens()).isEqualTo(300);
		assertThat(Usage.ZERO.plus(Usage.ZERO)).isEqualTo(Usage.ZERO);
		assertThatThrownBy(() -> new Usage(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void totalTokensDoNotOverflowAnInt() {
		assertThat(new Usage(Integer.MAX_VALUE, Integer.MAX_VALUE, 0).totalTokens())
				.isEqualTo(2L * Integer.MAX_VALUE);
	}

	@Test
	void responsesExposeToolCallsAndText() {
		ContentPart.ToolCall first = new ContentPart.ToolCall("c1", "get_issue", json("{\"n\":7}"));
		ContentPart.ToolCall second = new ContentPart.ToolCall("c2", "list_labels", json("{}"));
		LlmResponse response = new LlmResponse(
				List.of(new ContentPart.Text("Let me look."), first, new ContentPart.Opaque("thinking", json("{}")),
						second, new ContentPart.Text("Done.")),
				StopReason.TOOL_USE, new Usage(10, 5, 0));

		assertThat(response.toolCalls()).containsExactly(first, second);
		assertThat(response.text()).isEqualTo("Let me look.\nDone.");
	}

	@Test
	void responseFactoriesSetTheStopReason() {
		Usage usage = new Usage(1, 1, 0);

		assertThat(LlmResponse.ofText("ok", usage).stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(LlmResponse.ofToolCall("c", "t", json("{}"), usage).stopReason()).isEqualTo(StopReason.TOOL_USE);
		assertThat(LlmResponse.ofToolCall("c", "t", json("{}"), usage).toolCalls()).hasSize(1);
		assertThat(LlmResponse.ofRefusal("no", usage).stopReason()).isEqualTo(StopReason.REFUSAL);
	}

	@Test
	void aResponseWithoutTextHasEmptyText() {
		assertThat(LlmResponse.ofToolCall("c", "t", json("{}"), Usage.ZERO).text()).isEmpty();
	}
}
