package cz.cvut.pmassistant.llm.gateway;

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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import cz.cvut.pmassistant.config.AppProperties;
import cz.cvut.pmassistant.llm.LlmClient;
import cz.cvut.pmassistant.llm.model.LlmMessage;
import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.LlmResponse;

/**
 * The real configuration path: the project's own {@code application.yml} (profile {@code test}) maps
 * {@code app.llm.*} into {@code spring.ai.openai.*}, Spring AI builds the chat model from it, and our client sits on
 * top. Only the web address, the key and the model name are set here, the way the {@code LLM_*} variables would.
 */
class LlmAutoConfigurationTest {

	private static final String PATH = "/v1/chat/completions";

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AppProperties.class)
	static class PropertiesConfig {
	}

	@RegisterExtension
	static WireMockExtension server = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private ApplicationContextRunner runner(String... properties) {
		return new ApplicationContextRunner()
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.withPropertyValues("spring.profiles.active=test")
				.withConfiguration(AutoConfigurations.of(OpenAiChatAutoConfiguration.class,
						ToolCallingAutoConfiguration.class))
				.withUserConfiguration(PropertiesConfig.class, LlmConfig.class)
				.withPropertyValues(properties);
	}

	private static LlmRequest request() {
		return new LlmRequest("You triage.", List.of(LlmMessage.user("hi")), List.of(), 321);
	}

	private static void stubAnswer() {
		server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(OpenAiFixtures.read("chat-text.json"))));
	}

	@Test
	void theSettingsOfTheProjectReachTheModelAndTheClientTalksToTheConfiguredServer() {
		stubAnswer();

		runner("app.llm.base-url=" + server.baseUrl() + "/v1", "app.llm.api-key=my-key", "app.llm.model=gpt-test")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
					LlmClient client = context.getBean(LlmClient.class);

					LlmResponse response = client.chat(request());

					assertThat(client.model()).isEqualTo("gpt-test");
					assertThat(response.text()).isNotEmpty();
					server.verify(postRequestedFor(urlEqualTo(PATH))
							.withHeader("Authorization", equalTo("Bearer my-key"))
							.withRequestBody(matchingJsonPath("$.model", equalTo("gpt-test")))
							.withRequestBody(matchingJsonPath("$.max_tokens", equalTo("321"))));
				});
	}

	@Test
	void aLocalServerWithoutAnApiKeyStartsAndIsCalledWithoutACredential() {
		stubAnswer();

		runner("app.llm.base-url=" + server.baseUrl() + "/v1", "app.llm.model=llama3").run(context -> {
			assertThat(context).hasNotFailed();

			context.getBean(LlmClient.class).chat(request());

			server.verify(postRequestedFor(urlEqualTo(PATH))
					.withRequestBody(matchingJsonPath("$.model", equalTo("llama3"))));
		});
	}

	@Test
	void theNameOfTheLimitFieldCanBeChangedForNewerOpenAiModels() {
		stubAnswer();

		runner("app.llm.base-url=" + server.baseUrl() + "/v1", "app.llm.model=gpt-test",
				"app.llm.max-tokens-parameter=max_completion_tokens").run(context -> {
					context.getBean(LlmClient.class).chat(request());

					server.verify(postRequestedFor(urlEqualTo(PATH))
							.withRequestBody(matchingJsonPath("$.max_completion_tokens", equalTo("321")))
							.withRequestBody(matchingJsonPath("$[?(!@.max_tokens)]")));
				});
	}

	@Test
	void anUnknownLimitFieldNameStopsTheApplicationAtStartup() {
		runner("app.llm.base-url=http://localhost:1/v1", "app.llm.model=m", "app.llm.max-tokens-parameter=nonsense")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void onlyTheChatModelIsCreatedNotTheOtherOpenAiFeatures() {
		runner("app.llm.base-url=http://localhost:1/v1", "app.llm.model=m").run(context -> {
			assertThat(context.getBeanNamesForType(org.springframework.ai.embedding.EmbeddingModel.class)).isEmpty();
			assertThat(context.getBeanNamesForType(org.springframework.ai.image.ImageModel.class)).isEmpty();
		});
	}
}
