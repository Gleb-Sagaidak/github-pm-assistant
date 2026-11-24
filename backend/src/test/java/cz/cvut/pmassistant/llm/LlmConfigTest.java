package cz.cvut.pmassistant.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import cz.cvut.pmassistant.config.AppProperties;
import cz.cvut.pmassistant.llm.model.LlmMessage;
import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.LlmResponse;

/** The model is configured through app.llm.* and nothing in the code names a provider. */
class LlmConfigTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AppProperties.class)
	static class PropertiesConfig {
	}

	private static final String ANSWER = """
			{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
			 "usage":{"prompt_tokens":3,"completion_tokens":1}}""";

	@RegisterExtension
	static WireMockExtension server = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private ApplicationContextRunner runner(String... llmProperties) {
		return new ApplicationContextRunner()
				.withUserConfiguration(PropertiesConfig.class, LlmConfig.class)
				.withPropertyValues(
						"app.github.app-id=1",
						"app.github.app-slug=my-app",
						"app.github.private-key-path=key.pem",
						"app.github.client-id=cid",
						"app.github.client-secret=csecret",
						"app.github.api-version=2026-03-10",
						"app.defaults.daily-token-quota=500000")
				.withPropertyValues(llmProperties);
	}

	private static LlmRequest request() {
		return new LlmRequest("system", List.of(LlmMessage.user("hi")), List.of(), 321);
	}

	@Test
	void theClientIsBuiltFromTheSettingsAndTalksToTheConfiguredServer() {
		server.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(ANSWER)));

		runner("app.llm.base-url=" + server.baseUrl() + "/v1", "app.llm.api-key=my-key", "app.llm.model=gpt-test")
				.run(context -> {
					LlmClient client = context.getBean(LlmClient.class);

					LlmResponse response = client.chat(request());

					assertThat(client.model()).isEqualTo("gpt-test");
					assertThat(response.text()).isEqualTo("ok");
					server.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
							.withHeader("Authorization", equalTo("Bearer my-key"))
							.withRequestBody(matchingJsonPath("$.model", equalTo("gpt-test")))
							.withRequestBody(matchingJsonPath("$.max_tokens", equalTo("321"))));
				});
	}

	@Test
	void aLocalServerNeedsNoApiKey() {
		server.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(ANSWER)));

		runner("app.llm.base-url=" + server.baseUrl() + "/v1", "app.llm.model=llama3").run(context -> {
			context.getBean(LlmClient.class).chat(request());

			server.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
					.withHeader("Authorization", absent()));
		});
	}

	@Test
	void theNameOfTheLimitFieldCanBeChanged() {
		server.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(ANSWER)));

		runner("app.llm.base-url=" + server.baseUrl() + "/v1", "app.llm.model=gpt-test",
				"app.llm.max-tokens-parameter=max_completion_tokens").run(context -> {
					context.getBean(LlmClient.class).chat(request());

					server.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
							.withRequestBody(matchingJsonPath("$.max_completion_tokens", equalTo("321")))
							.withRequestBody(matchingJsonPath("$[?(!@.max_tokens)]")));
				});
	}

	@Test
	void timeoutAndRetriesAreOptionalSettings() {
		runner("app.llm.base-url=http://localhost:1/v1", "app.llm.model=m", "app.llm.timeout=45s",
				"app.llm.max-retries=0").run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void theApplicationDoesNotStartWithoutAModelOrAnAddress() {
		runner("app.llm.base-url=http://localhost:1/v1").run(context -> assertThat(context).hasFailed());
		runner("app.llm.model=m").run(context -> assertThat(context).hasFailed());
	}
}
