package cz.cvut.pmassistant.agent.persistence;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** A real Postgres for the storage tests, so the Flyway migration and the JSON columns are exercised for real. */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfig {

	static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
	}

	@Bean
	Clock clock() {
		return Clock.fixed(NOW, ZoneOffset.UTC);
	}
}
