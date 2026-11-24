package cz.cvut.pmassistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The application must not start without required values (secrets come from the environment only). */
class AppPropertiesTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AppProperties.class)
	static class Config {
	}

	private static final String[] ALL_REQUIRED = {
			"app.github.app-id=1",
			"app.github.app-slug=my-app",
			"app.github.private-key-path=key.pem",
			"app.github.client-id=cid",
			"app.github.client-secret=csecret",
			"app.github.api-version=2026-03-10",
			"app.llm.base-url=https://api.openai.com/v1",
			"app.llm.model=gpt-test",
			"app.defaults.daily-token-quota=500000"
	};

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void bindsWhenAllRequiredValuesPresent() {
		runner.withPropertyValues(ALL_REQUIRED).run(ctx -> {
			assertThat(ctx).hasNotFailed();
			AppProperties props = ctx.getBean(AppProperties.class);
			assertThat(props.github().appSlug()).isEqualTo("my-app");
			assertThat(props.github().webhookSecret()).isNull();
			assertThat(props.defaults().dailyTokenQuota()).isEqualTo(500000);
			assertThat(props.llm().apiKey()).as("the key is optional").isNull();
		});
	}

	@Test
	void failsWithoutLlmModel() {
		String[] without = Arrays.stream(ALL_REQUIRED)
				.filter(p -> !p.startsWith("app.llm.model"))
				.toArray(String[]::new);
		runner.withPropertyValues(without).run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	void failsWithoutLlmBaseUrl() {
		String[] without = Arrays.stream(ALL_REQUIRED)
				.filter(p -> !p.startsWith("app.llm.base-url"))
				.toArray(String[]::new);
		runner.withPropertyValues(without).run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	void bindsAnApiKeyWhenOneIsGiven() {
		runner.withPropertyValues(ALL_REQUIRED).withPropertyValues("app.llm.api-key=sk-test").run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(AppProperties.class).llm().apiKey()).isEqualTo("sk-test");
		});
	}

	@Test
	void failsWithBlankGithubAppId() {
		runner.withPropertyValues(ALL_REQUIRED).withPropertyValues("app.github.app-id=")
				.run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	void failsWithNonPositiveQuota() {
		runner.withPropertyValues(ALL_REQUIRED).withPropertyValues("app.defaults.daily-token-quota=0")
				.run(ctx -> assertThat(ctx).hasFailed());
	}

	/** Spring does not fail on an unresolved ${VAR} while binding; it keeps the literal. The literal must not count as a value. */
	@Test
	void failsWhenPlaceholderStaysUnresolved() {
		runner.withPropertyValues(ALL_REQUIRED)
				.withPropertyValues("app.llm.model=${LLM_MODEL_NOT_SET_IN_TEST}")
				.run(ctx -> assertThat(ctx).hasFailed());
	}
}
