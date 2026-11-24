package cz.cvut.pmassistant.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import cz.cvut.pmassistant.config.AppProperties;

class GitHubConfigTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AppProperties.class)
	static class PropertiesConfig {
	}

	private ApplicationContextRunner runner(String keyPath) {
		return new ApplicationContextRunner()
				.withUserConfiguration(PropertiesConfig.class, GitHubConfig.class)
				.withPropertyValues(
						"app.github.app-id=1",
						"app.github.app-slug=my-app",
						"app.github.private-key-path=" + keyPath,
						"app.github.client-id=Iv1.test",
						"app.github.client-secret=secret",
						"app.github.api-version=2026-03-10",
						"app.llm.base-url=http://localhost:11434/v1",
						"app.llm.model=test-model",
						"app.defaults.daily-token-quota=500000");
	}

	@Test
	void theApplicationStartsWithoutAPrivateKeyBecauseTheClientsAreLazy() {
		runner("does-not-exist.pem").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeanFactory().getBeanDefinition("gitHubClients").isLazyInit()).isTrue();
			assertThat(context.getBeanFactory().getBeanDefinition("appJwtFactory").isLazyInit()).isTrue();
		});
	}

	@Test
	void theClientsAreBuiltFromTheConfiguredKey(@TempDir Path dir) throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		byte[] der = generator.generateKeyPair().getPrivate().getEncoded();
		String pem = "-----BEGIN PRIVATE KEY-----\n"
				+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
				+ "\n-----END PRIVATE KEY-----\n";
		Path key = dir.resolve("app.pem");
		Files.writeString(key, pem, StandardCharsets.US_ASCII);

		runner(key.toString().replace('\\', '/')).run(context -> {
			GitHubClients clients = context.getBean(GitHubClients.class);

			assertThat(clients.appClient()).isNotNull();
			assertThat(clients.readerFor(1)).isNotNull();
		});
	}

	@Test
	void aMissingKeyFileFailsWhenTheClientsAreFirstNeeded() {
		runner("does-not-exist.pem").run(context -> assertThatThrownBy(() -> context.getBean(GitHubClients.class))
				.isInstanceOf(BeanCreationException.class));
	}
}
