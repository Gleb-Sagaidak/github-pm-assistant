package cz.cvut.pmassistant.llm.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import cz.cvut.pmassistant.llm.LlmUnavailableException;
import cz.cvut.pmassistant.llm.model.LlmMessage;
import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.LlmResponse;
import cz.cvut.pmassistant.llm.model.Usage;

class ScriptedLlmClientTest {

	private static LlmRequest request(String text) {
		return new LlmRequest("system", List.of(LlmMessage.user(text)), List.of(), 100);
	}

	@Test
	void givesTheScriptedAnswersInOrderAndRecordsTheRequests() {
		LlmResponse first = LlmResponse.ofText("one", Usage.ZERO);
		LlmResponse second = LlmResponse.ofText("two", Usage.ZERO);
		ScriptedLlmClient client = ScriptedLlmClient.of(first, second);

		assertThat(client.chat(request("a"))).isSameAs(first);
		assertThat(client.chat(request("b"))).isSameAs(second);

		assertThat(client.callCount()).isEqualTo(2);
		assertThat(client.requests()).extracting(r -> r.messages().get(0).parts().get(0))
				.hasSize(2);
		assertThat(client.lastRequest()).isEqualTo(request("b"));
		assertThat(client.remaining()).isZero();
	}

	@Test
	void throwsScriptedFailures() {
		LlmUnavailableException failure = new LlmUnavailableException(LlmUnavailableException.Reason.TIMEOUT, "slow");
		ScriptedLlmClient client = ScriptedLlmClient.of(failure, LlmResponse.ofText("recovered", Usage.ZERO));

		assertThatThrownBy(() -> client.chat(request("a"))).isSameAs(failure);
		assertThat(client.chat(request("b")).text()).isEqualTo("recovered");
	}

	@Test
	void aRequestBeyondTheScriptIsAnError() {
		ScriptedLlmClient client = ScriptedLlmClient.of(LlmResponse.ofText("only", Usage.ZERO));
		client.chat(request("a"));

		assertThatThrownBy(() -> client.chat(request("b")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("exhausted");
		assertThat(client.callCount()).as("the unexpected request is still recorded").isEqualTo(2);
	}

	@Test
	void onlyResponsesAndExceptionsAreValidSteps() {
		assertThatThrownBy(() -> ScriptedLlmClient.of("a string")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theRecordedListCannotBeChangedByTheCaller() {
		ScriptedLlmClient client = ScriptedLlmClient.of(LlmResponse.ofText("x", Usage.ZERO));
		client.chat(request("a"));

		assertThatThrownBy(() -> client.requests().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void lastRequestNeedsAtLeastOneRequest() {
		assertThatThrownBy(() -> ScriptedLlmClient.of().lastRequest()).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void reportsAModelNameForRecording() {
		assertThat(ScriptedLlmClient.of().model()).isEqualTo("scripted");
	}
}
