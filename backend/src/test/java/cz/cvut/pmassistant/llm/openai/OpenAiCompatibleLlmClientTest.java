package cz.cvut.pmassistant.llm.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import cz.cvut.pmassistant.llm.LlmUnavailableException;
import cz.cvut.pmassistant.llm.LlmUnavailableException.Reason;
import cz.cvut.pmassistant.llm.model.LlmMessage;
import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.LlmResponse;
import cz.cvut.pmassistant.llm.model.StopReason;
import cz.cvut.pmassistant.llm.model.Usage;

class OpenAiCompatibleLlmClientTest {

	private static final String PATH = "/v1/chat/completions";
	private static final String SECRET = "sk-very-secret-key";

	@RegisterExtension
	static WireMockExtension server = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private final List<Duration> sleeps = new ArrayList<>();

	private OpenAiCompatibleLlmClient client(String apiKey, int maxRetries, Duration timeout) {
		OpenAiCompatibleSettings settings = new OpenAiCompatibleSettings(server.baseUrl() + "/v1", apiKey, "gpt-test",
				"max_tokens", timeout, maxRetries);
		return new OpenAiCompatibleLlmClient(settings, sleeps::add);
	}

	private OpenAiCompatibleLlmClient client(int maxRetries) {
		return client(SECRET, maxRetries, Duration.ofSeconds(10));
	}

	private static LlmRequest request() {
		return new LlmRequest("You triage issues.", List.of(LlmMessage.user("Triage issue 7")), List.of(), 500);
	}

	private static ResponseDefinitionBuilder json(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
	}

	private static ResponseDefinitionBuilder error(int status, String body) {
		return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
	}

	@Test
	void postsToChatCompletionsWithTheKeyAndTheMappedBody() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(json(OpenAiFixtures.read("chat-text.json"))));

		LlmResponse response = client(2).chat(request());

		assertThat(response.text()).isEqualTo("It looks like a bug in the login flow.");
		assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(response.usage()).isEqualTo(new Usage(120, 12, 100));
		server.verify(postRequestedFor(urlEqualTo(PATH))
				.withHeader("Authorization", equalTo("Bearer " + SECRET))
				.withHeader("Content-Type", equalTo("application/json"))
				.withRequestBody(equalToJson("""
						{"model":"gpt-test","max_tokens":500,
						 "messages":[{"role":"system","content":"You triage issues."},
						             {"role":"user","content":"Triage issue 7"}]}""")));
	}

	@Test
	void aServerThatNeedsNoKeyGetsNoAuthorizationHeader() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(json(OpenAiFixtures.read("chat-text.json"))));

		client(null, 0, Duration.ofSeconds(10)).chat(request());
		client("  ", 0, Duration.ofSeconds(10)).chat(request());

		server.verify(2, postRequestedFor(urlEqualTo(PATH)).withHeader("Authorization", absent()));
	}

	@Test
	void reportsTheConfiguredModel() {
		assertThat(client(0).model()).isEqualTo("gpt-test");
	}

	@Test
	void badCredentialsAreNotRetriedAndTheKeyIsNotLeaked() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(error(401,
				"{\"error\":{\"message\":\"Incorrect API key provided.\",\"type\":\"invalid_request_error\"}}")));

		assertThatThrownBy(() -> client(2).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class, e -> {
					assertThat(e.reason()).isEqualTo(Reason.UNAUTHORIZED);
					assertThat(e.getMessage()).contains("401", "Incorrect API key");
					assertThat(e.getMessage()).doesNotContain(SECRET);
				});
		server.verify(1, postRequestedFor(urlEqualTo(PATH)));
		assertThat(sleeps).isEmpty();
	}

	@Test
	void aRejectedRequestIsNotRetriedAndCarriesTheProvidersExplanation() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(error(400,
				"{\"error\":{\"message\":\"Unsupported parameter: max_tokens. Use max_completion_tokens.\"}}")));

		assertThatThrownBy(() -> client(2).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class, e -> {
					assertThat(e.reason()).isEqualTo(Reason.REJECTED);
					assertThat(e.getMessage()).contains("400", "max_completion_tokens");
				});
		server.verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	@Test
	void anUnknownModelIsRejected() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(error(404, "{\"error\":\"model 'gpt-test' not found\"}")));

		assertThatThrownBy(() -> client(2).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class, e -> {
					assertThat(e.reason()).isEqualTo(Reason.REJECTED);
					assertThat(e.getMessage()).contains("model 'gpt-test' not found");
				});
	}

	@Test
	void aRateLimitIsRetriedAfterTheTimeTheServerAsksFor() {
		server.stubFor(post(urlEqualTo(PATH)).inScenario("limit").whenScenarioStateIs(STARTED)
				.willReturn(error(429, "{\"error\":{\"message\":\"Rate limit reached\"}}").withHeader("Retry-After", "2"))
				.willSetStateTo("ok"));
		server.stubFor(post(urlEqualTo(PATH)).inScenario("limit").whenScenarioStateIs("ok")
				.willReturn(json(OpenAiFixtures.read("chat-text.json"))));

		LlmResponse response = client(2).chat(request());

		assertThat(response.text()).isNotEmpty();
		assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
	}

	@Test
	void aRateLimitThatLastsTooLongFailsAtOnce() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(error(429, "{}").withHeader("Retry-After", "120")));

		assertThatThrownBy(() -> client(2).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class,
						e -> assertThat(e.reason()).isEqualTo(Reason.RATE_LIMITED));
		server.verify(1, postRequestedFor(urlEqualTo(PATH)));
		assertThat(sleeps).isEmpty();
	}

	@Test
	void serverErrorsAreRetriedWithGrowingPausesThenReported() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(error(500, "{\"error\":{\"message\":\"overloaded\"}}")));

		assertThatThrownBy(() -> client(2).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class, e -> {
					assertThat(e.reason()).isEqualTo(Reason.SERVER_ERROR);
					assertThat(e.getMessage()).contains("500", "overloaded");
				});
		server.verify(3, postRequestedFor(urlEqualTo(PATH)));
		assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
	}

	@Test
	void aServerErrorThatPassesIsHiddenFromTheCaller() {
		server.stubFor(post(urlEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs(STARTED)
				.willReturn(error(503, "unavailable")).willSetStateTo("ok"));
		server.stubFor(post(urlEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs("ok")
				.willReturn(json(OpenAiFixtures.read("chat-tool-call.json"))));

		LlmResponse response = client(2).chat(request());

		assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
		assertThat(sleeps).hasSize(1);
	}

	@Test
	void withoutRetriesAFailureIsReportedAtOnce() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(error(500, "{}")));

		assertThatThrownBy(() -> client(0).chat(request())).isInstanceOf(LlmUnavailableException.class);

		server.verify(1, postRequestedFor(urlEqualTo(PATH)));
		assertThat(sleeps).isEmpty();
	}

	@Test
	void aDroppedConnectionIsANetworkFailureAfterTheRetries() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

		assertThatThrownBy(() -> client(1).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class, e -> {
					assertThat(e.reason()).isEqualTo(Reason.NETWORK);
					assertThat(e.getMessage()).doesNotContain(SECRET);
				});
		assertThat(sleeps).hasSize(1);
	}

	@Test
	void aModelThatAnswersTooSlowlyIsATimeout() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(json(OpenAiFixtures.read("chat-text.json"))
				.withFixedDelay(1500)));

		assertThatThrownBy(() -> client(SECRET, 0, Duration.ofMillis(300)).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class, e -> {
					assertThat(e.reason()).isEqualTo(Reason.TIMEOUT);
					assertThat(e.getMessage()).contains("did not answer");
				});
	}

	@Test
	void anAnswerThatIsNotJsonIsNotRetried() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("<html>proxy</html>")));

		assertThatThrownBy(() -> client(2).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class,
						e -> assertThat(e.reason()).isEqualTo(Reason.OTHER));
		server.verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	@Test
	void aRedirectIsNotFollowedSoTheKeyStaysWithTheConfiguredServer() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(307)
				.withHeader("Location", "https://elsewhere.example/v1/chat/completions")));

		assertThatThrownBy(() -> client(2).chat(request()))
				.isInstanceOfSatisfying(LlmUnavailableException.class,
						e -> assertThat(e.reason()).isEqualTo(Reason.REJECTED));
		server.verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	@Test
	void aTrailingSlashInTheBaseUrlIsHarmless() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(json(OpenAiFixtures.read("chat-text.json"))));
		OpenAiCompatibleSettings settings = OpenAiCompatibleSettings.of(server.baseUrl() + "/v1/", null, "gpt-test");

		assertThat(new OpenAiCompatibleLlmClient(settings).chat(request()).text()).isNotEmpty();
	}

	@Test
	void theKeyDoesNotAppearWhenSettingsArePrinted() {
		OpenAiCompatibleSettings settings = OpenAiCompatibleSettings.of("http://x/v1", SECRET, "gpt-test");

		assertThat(settings.toString()).doesNotContain(SECRET).contains("<set>");
		assertThat(OpenAiCompatibleSettings.of("http://x/v1", null, "m").toString()).contains("<none>");
	}

	@Test
	void settingsRejectNonsense() {
		assertThatThrownBy(() -> OpenAiCompatibleSettings.of(" ", null, "m")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OpenAiCompatibleSettings.of("http://x", null, "")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OpenAiCompatibleSettings("http://x", null, "m", "max_tokens", Duration.ZERO, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OpenAiCompatibleSettings("http://x", null, "m", "max_tokens", Duration.ofSeconds(1), -1))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
