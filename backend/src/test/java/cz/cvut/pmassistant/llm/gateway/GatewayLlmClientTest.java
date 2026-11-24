package cz.cvut.pmassistant.llm.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import cz.cvut.pmassistant.llm.LlmUnavailableException;
import cz.cvut.pmassistant.llm.LlmUnavailableException.Reason;
import cz.cvut.pmassistant.llm.model.LlmMessage;
import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.LlmResponse;
import cz.cvut.pmassistant.llm.model.StopReason;
import cz.cvut.pmassistant.llm.model.Usage;

class GatewayLlmClientTest {

	private static LlmRequest request() {
		return new LlmRequest("You triage.", List.of(LlmMessage.user("hi")), List.of(), 500);
	}

	private static GatewayLlmClient client(StubChatModel model, ErrorClassifier errors) {
		return new GatewayLlmClient(model, "gpt-test", errors, OutputLimit.maxTokens());
	}

	@Test
	void anAnswerIsTranslatedAndTheModelNameIsTheConfiguredOne() {
		StubChatModel model = StubChatModel.answering(StubChatModel.text("Looks like a bug.", "STOP", 20, 5));
		GatewayLlmClient client = client(model, ErrorClassifier.none());

		LlmResponse response = client.chat(request());

		assertThat(response.text()).isEqualTo("Looks like a bug.");
		assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(response.usage()).isEqualTo(new Usage(20, 5, 0));
		assertThat(client.model()).isEqualTo("gpt-test");
		assertThat(model.lastPrompt().getInstructions()).hasSize(2);
	}

	@Test
	void aFailureTheClassifierRecognisesBecomesAnLlmUnavailableException() {
		RuntimeException providerFailure = new IllegalStateException("429 from the provider");
		ErrorClassifier classifier = failure -> Optional.of(
				new LlmUnavailableException(Reason.RATE_LIMITED, failure.getMessage(), failure));

		assertThatThrownBy(() -> client(StubChatModel.failing(providerFailure), classifier).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class, e -> {
					assertThat(e.reason()).isEqualTo(Reason.RATE_LIMITED);
					assertThat(e.getCause()).isSameAs(providerFailure);
				});
	}

	@Test
	void anExceptionTheClassifierDoesNotRecogniseIsABugAndPropagatesUnchanged() {
		RuntimeException bug = new IllegalArgumentException("not a service failure");

		assertThatThrownBy(() -> client(StubChatModel.failing(bug), ErrorClassifier.none()).chat(request()))
				.isSameAs(bug);
	}

	@Test
	void anExceptionThatIsAlreadyTranslatedIsNotTranslatedTwice() {
		LlmUnavailableException translated = new LlmUnavailableException(Reason.TIMEOUT, "slow");
		ErrorClassifier neverAsked = failure -> {
			throw new AssertionError("must not be asked");
		};

		assertThatThrownBy(() -> client(StubChatModel.failing(translated), neverAsked).chat(request()))
				.isSameAs(translated);
	}

	@Test
	void anOpenAiOnlyOutputLimitIsRefusedForAnotherModelAndNothingIsSent() {
		StubChatModel model = StubChatModel.answering(StubChatModel.text("x", "STOP", 1, 1));
		GatewayLlmClient client = new GatewayLlmClient(model, "m", ErrorClassifier.none(),
				OutputLimit.maxCompletionTokens());

		// the stub is not an OpenAI model, so the OpenAI-only output limit must be refused, not silently ignored
		assertThatThrownBy(() -> client.chat(request())).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("max_completion_tokens");
		assertThat(model.prompts).isEmpty();
	}
}
