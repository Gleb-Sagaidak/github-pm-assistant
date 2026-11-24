package cz.cvut.pmassistant.github;

import java.nio.file.Path;
import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import cz.cvut.pmassistant.config.AppProperties;
import cz.cvut.pmassistant.github.auth.AppJwtFactory;
import cz.cvut.pmassistant.github.http.RetryExecutor;

/**
 * Makes the GitHub clients available to the rest of the application. The beans are lazy: the private key file
 * is read when the first consumer needs the clients, not at startup, so tests and tools that never talk to
 * GitHub do not need a key.
 */
@Configuration(proxyBeanMethods = false)
public class GitHubConfig {

	@Bean
	@ConditionalOnMissingBean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	@Lazy
	AppJwtFactory appJwtFactory(AppProperties properties, Clock clock) {
		// GitHub recommends the Client ID as the JWT issuer.
		return AppJwtFactory.fromPemFile(properties.github().clientId(),
				Path.of(properties.github().privateKeyPath()), clock);
	}

	@Bean
	@Lazy
	GitHubClients gitHubClients(AppProperties properties, AppJwtFactory appJwtFactory, Clock clock,
			@Value("${app.github.api-base-url:https://api.github.com}") String apiBaseUrl) {
		return new GitHubClients(apiBaseUrl, properties.github().apiVersion(), appJwtFactory, clock,
				RetryExecutor.standard());
	}
}
