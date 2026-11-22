package cz.cvut.pmassistant.github.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/** The retry policy against a real HTTP exchange (NFR6); waiting is recorded, not performed. */
class GitHubHttpRetryTest {

	private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

	@RegisterExtension
	static WireMockExtension github = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private final List<Duration> sleeps = new ArrayList<>();
	private final AtomicInteger tokenCalls = new AtomicInteger();

	private GitHubHttp http() {
		RetryExecutor retry = new RetryExecutor(4, Duration.ofSeconds(1), Duration.ofSeconds(60), sleeps::add,
				() -> 0);
		return new GitHubHttp(github.baseUrl(), "2026-03-10", () -> "tok-" + tokenCalls.incrementAndGet(),
				Clock.fixed(NOW, ZoneOffset.UTC), retry);
	}

	private static void stubSecondAttemptSucceeds(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder first) {
		github.stubFor(get("/x").inScenario("flaky").whenScenarioStateIs(STARTED)
				.willReturn(first).willSetStateTo("recovered"));
		github.stubFor(get("/x").inScenario("flaky").whenScenarioStateIs("recovered")
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain")
						.withBody("done")));
	}

	@Test
	void retriesA429HonouringRetryAfterAndSucceeds() {
		stubSecondAttemptSucceeds(aResponse().withStatus(429).withHeader("retry-after", "1"));

		String result = http().get("/x", String.class);

		assertThat(result).isEqualTo("done");
		assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
		github.verify(2, getRequestedFor(urlEqualTo("/x")));
	}

	@Test
	void waitsUntilRateLimitResetOn403() {
		long reset = NOW.plusSeconds(5).getEpochSecond();
		stubSecondAttemptSucceeds(aResponse().withStatus(403)
				.withHeader("x-ratelimit-remaining", "0")
				.withHeader("x-ratelimit-reset", String.valueOf(reset))
				.withBody("{\"message\":\"API rate limit exceeded\"}"));

		assertThat(http().get("/x", String.class)).isEqualTo("done");
		assertThat(sleeps).containsExactly(Duration.ofSeconds(5));
	}

	@Test
	void failsWithoutWaitingWhenResetIsTooFarAway() {
		long reset = NOW.plusSeconds(600).getEpochSecond();
		github.stubFor(get("/x").willReturn(aResponse().withStatus(403)
				.withHeader("x-ratelimit-remaining", "0")
				.withHeader("x-ratelimit-reset", String.valueOf(reset))
				.withBody("{\"message\":\"API rate limit exceeded\"}")));

		assertThatThrownBy(() -> http().get("/x", String.class))
				.isInstanceOf(GitHubApiException.RateLimited.class);

		assertThat(sleeps).isEmpty();
		github.verify(1, getRequestedFor(urlEqualTo("/x")));
	}

	@Test
	void gives503UpAfterFourAttemptsAsTransient() {
		github.stubFor(get("/x").willReturn(aResponse().withStatus(503)));

		assertThatThrownBy(() -> http().get("/x", String.class))
				.isInstanceOfSatisfying(GitHubApiException.Transient.class,
						e -> assertThat(e.status()).isEqualTo(503));

		github.verify(4, getRequestedFor(urlEqualTo("/x")));
		assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
	}

	@Test
	void retriesNetworkFailures() {
		// The JDK HttpClient silently repeats an idempotent request once after a connection reset, so two
		// resets in a row are needed to make sure at least one failure reaches our own retry logic.
		github.stubFor(get("/x").inScenario("resets").whenScenarioStateIs(STARTED)
				.willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)).willSetStateTo("second"));
		github.stubFor(get("/x").inScenario("resets").whenScenarioStateIs("second")
				.willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)).willSetStateTo("third"));
		github.stubFor(get("/x").inScenario("resets").whenScenarioStateIs("third")
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain")
						.withBody("done")));

		assertThat(http().get("/x", String.class)).isEqualTo("done");
		assertThat(sleeps).isNotEmpty().allMatch(d -> d.compareTo(Duration.ofSeconds(1)) >= 0);
	}

	@Test
	void doesNotRetryNotFound() {
		github.stubFor(get("/x").willReturn(aResponse().withStatus(404)));

		assertThatThrownBy(() -> http().get("/x", String.class)).isInstanceOf(GitHubApiException.NotFound.class);

		github.verify(1, getRequestedFor(urlEqualTo("/x")));
		assertThat(sleeps).isEmpty();
	}

	@Test
	void doesNotRetryUnauthorizedOrValidationErrors() {
		github.stubFor(get("/unauthorized").willReturn(aResponse().withStatus(401)));
		github.stubFor(get("/invalid").willReturn(aResponse().withStatus(422)));

		assertThatThrownBy(() -> http().get("/unauthorized", String.class))
				.isInstanceOf(GitHubApiException.Unauthorized.class);
		assertThatThrownBy(() -> http().get("/invalid", String.class)).isInstanceOf(GitHubApiException.class);

		github.verify(1, getRequestedFor(urlEqualTo("/unauthorized")));
		github.verify(1, getRequestedFor(urlEqualTo("/invalid")));
		assertThat(sleeps).isEmpty();
	}

	@Test
	void asksForAFreshTokenOnEveryAttempt() {
		stubSecondAttemptSucceeds(aResponse().withStatus(500));

		http().get("/x", String.class);

		github.verify(getRequestedFor(urlEqualTo("/x")).withHeader("Authorization", equalTo("Bearer tok-1")));
		github.verify(getRequestedFor(urlEqualTo("/x")).withHeader("Authorization", equalTo("Bearer tok-2")));
	}
}
