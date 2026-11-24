package cz.cvut.pmassistant.llm.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import cz.cvut.pmassistant.llm.LlmUnavailableException;
import cz.cvut.pmassistant.llm.LlmUnavailableException.Reason;
import cz.cvut.pmassistant.llm.model.ContentPart;
import cz.cvut.pmassistant.llm.model.LlmMessage;
import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.LlmResponse;
import cz.cvut.pmassistant.llm.model.StopReason;
import cz.cvut.pmassistant.llm.model.ToolSpec;
import cz.cvut.pmassistant.llm.model.Usage;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spring AI's real OpenAI module talking to an OpenAI-style server (WireMock): what goes over the wire, what comes
 * back, and how failures arrive. This is the part no stub can show.
 */
class OpenAiWireTest {

	private static final String PATH = "/v1/chat/completions";
	private static final String SECRET = "sk-very-secret-key";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@RegisterExtension
	static WireMockExtension server = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private GatewayLlmClient client(int maxRetries, Duration timeout, OutputLimit limit) {
		OpenAiChatModel model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
				.baseUrl(server.baseUrl() + "/v1").apiKey(SECRET).model("gpt-test")
				.maxRetries(maxRetries).timeout(timeout).build()).build();
		return new GatewayLlmClient(model, "gpt-test", new OpenAiErrorClassifier(), limit);
	}

	private GatewayLlmClient client(int maxRetries) {
		return client(maxRetries, Duration.ofSeconds(10), OutputLimit.maxTokens());
	}

	private static LlmRequest request() {
		return new LlmRequest("You triage issues.", List.of(LlmMessage.user("Triage issue 7")), List.of(), 500);
	}

	private static ResponseDefinitionBuilder json(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
	}

	private static ResponseDefinitionBuilder error(int status, String message) {
		return aResponse().withStatus(status).withHeader("Content-Type", "application/json")
				.withHeader("Retry-After", "0")
				.withBody("{\"error\":{\"message\":\"" + message + "\",\"type\":\"error\"}}");
	}

	@Test
	void aTextAnswerGoesOutWithTheKeyAndComesBackTranslated() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(json(OpenAiFixtures.read("chat-text.json"))));

		LlmResponse response = client(0).chat(request());

		assertThat(response.text()).isEqualTo("It looks like a bug in the login flow.");
		assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(response.usage()).isEqualTo(new Usage(120, 12, 100));
		server.verify(postRequestedFor(urlEqualTo(PATH))
				.withHeader("Authorization", equalTo("Bearer " + SECRET))
				.withRequestBody(matchingJsonPath("$.model", equalTo("gpt-test")))
				.withRequestBody(matchingJsonPath("$.max_tokens", equalTo("500")))
				.withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
				.withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("You triage issues.")))
				.withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("user")))
				.withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("Triage issue 7"))));
	}

	@Test
	void aToolCallComesBackWithItsIdAndArgumentsAndTheToolIsNotRunByTheModelClient() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(json(OpenAiFixtures.read("chat-tool-call.json"))));
		ToolSpec spec = new ToolSpec("get_issue", "Reads an issue", JSON.readTree(
				"{\"type\":\"object\",\"properties\":{\"number\":{\"type\":\"integer\"}}}"), false);

		LlmResponse response = client(0).chat(new LlmRequest("s", List.of(LlmMessage.user("go")), List.of(spec), 500));

		assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
		assertThat(response.toolCalls()).singleElement().satisfies(call -> {
			assertThat(call.id()).isEqualTo("call_abc123");
			assertThat(call.name()).isEqualTo("get_issue");
			assertThat(call.arguments()).isEqualTo(JSON.readTree("{\"number\":7,\"repo\":\"cvut-team/pm-assistant\"}"));
		});
		server.verify(1, postRequestedFor(urlEqualTo(PATH))
				.withRequestBody(matchingJsonPath("$.tools[0].function.name", equalTo("get_issue"))));
	}

	@Test
	void theConversationWithToolResultsGoesOutInTheOpenAiShape() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(json(OpenAiFixtures.read("chat-text.json"))));
		LlmRequest request = new LlmRequest("s", List.of(LlmMessage.user("go"),
				LlmMessage.assistant(List.of(new ContentPart.ToolCall("c1", "get_issue", JSON.readTree("{\"number\":7}")))),
				LlmMessage.toolResults(List.of(new ContentPart.ToolResult("c1", "issue text", false)))), List.of(), 500);

		client(0).chat(request);

		server.verify(postRequestedFor(urlEqualTo(PATH))
				.withRequestBody(matchingJsonPath("$.messages[2].role", equalTo("assistant")))
				.withRequestBody(matchingJsonPath("$.messages[2].tool_calls[0].id", equalTo("c1")))
				.withRequestBody(matchingJsonPath("$.messages[2].tool_calls[0].function.name", equalTo("get_issue")))
				.withRequestBody(matchingJsonPath("$.messages[3].role", equalTo("tool")))
				.withRequestBody(matchingJsonPath("$.messages[3].tool_call_id", equalTo("c1")))
				.withRequestBody(matchingJsonPath("$.messages[3].content", equalTo("issue text"))));
	}

	@Test
	void theLimitCanBeSentAsMaxCompletionTokensForNewerOpenAiModels() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(json(OpenAiFixtures.read("chat-text.json"))));

		client(0, Duration.ofSeconds(10), OutputLimit.maxCompletionTokens()).chat(request());

		server.verify(postRequestedFor(urlEqualTo(PATH))
				.withRequestBody(matchingJsonPath("$.max_completion_tokens", equalTo("500")))
				.withRequestBody(matchingJsonPath("$[?(!@.max_tokens)]")));
	}

	@Test
	void badCredentialsAreNotRetriedAndTheKeyIsNotLeaked() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(error(401, "Incorrect API key provided.")));

		assertThatThrownBy(() -> client(2).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class, e -> {
					assertThat(e.reason()).isEqualTo(Reason.UNAUTHORIZED);
					assertThat(e.getMessage()).contains("Incorrect API key");
					assertThat(e.getMessage()).doesNotContain(SECRET);
				});
		server.verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	@Test
	void aRejectedRequestIsNotRetriedAndCarriesTheProvidersExplanation() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(error(400, "Unsupported parameter: max_tokens.")));

		assertThatThrownBy(() -> client(2).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class, e -> {
					assertThat(e.reason()).isEqualTo(Reason.REJECTED);
					assertThat(e.getMessage()).contains("Unsupported parameter");
				});
		server.verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	@Test
	void anUnknownModelIsRejected() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(error(404, "model not found")));

		assertThatThrownBy(() -> client(2).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class,
						e -> assertThat(e.reason()).isEqualTo(Reason.REJECTED));
	}

	@Test
	void aRateLimitIsRetriedByTheSdkAndThePassedRequestIsHiddenFromTheCaller() {
		server.stubFor(post(urlEqualTo(PATH)).inScenario("limit").whenScenarioStateIs(STARTED)
				.willReturn(error(429, "Rate limit reached")).willSetStateTo("ok"));
		server.stubFor(post(urlEqualTo(PATH)).inScenario("limit").whenScenarioStateIs("ok")
				.willReturn(json(OpenAiFixtures.read("chat-text.json"))));

		LlmResponse response = client(2).chat(request());

		assertThat(response.text()).isNotEmpty();
		server.verify(2, postRequestedFor(urlEqualTo(PATH)));
	}

	@Test
	void aRateLimitThatDoesNotEndIsReportedAfterTheRetries() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(error(429, "Rate limit reached")));

		assertThatThrownBy(() -> client(2).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class,
						e -> assertThat(e.reason()).isEqualTo(Reason.RATE_LIMITED));
		server.verify(3, postRequestedFor(urlEqualTo(PATH)));
	}

	@Test
	void serverErrorsAreRetriedThenReported() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(error(500, "overloaded")));

		assertThatThrownBy(() -> client(1).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class, e -> {
					assertThat(e.reason()).isEqualTo(Reason.SERVER_ERROR);
					assertThat(e.getMessage()).contains("overloaded");
				});
		server.verify(2, postRequestedFor(urlEqualTo(PATH)));
	}

	@Test
	void aDroppedConnectionIsANetworkFailure() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

		assertThatThrownBy(() -> client(0).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class, e -> {
					assertThat(e.reason()).isEqualTo(Reason.NETWORK);
					assertThat(e.getMessage()).doesNotContain(SECRET);
				});
	}

	@Test
	void aModelThatAnswersTooSlowlyIsATimeout() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(json(OpenAiFixtures.read("chat-text.json"))
				.withFixedDelay(1500)));

		assertThatThrownBy(() -> client(0, Duration.ofMillis(300), OutputLimit.maxTokens()).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class,
						e -> assertThat(e.reason()).isEqualTo(Reason.TIMEOUT));
	}

	@Test
	void anAnswerThatIsNotJsonIsAFailureNotACrash() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("<html>proxy</html>")));

		assertThatThrownBy(() -> client(0).chat(request())).isInstanceOf(LlmUnavailableException.class);
	}
}
