package cz.cvut.pmassistant.github.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import cz.cvut.pmassistant.github.http.GitHubApiException;
import cz.cvut.pmassistant.github.http.GitHubHttp;
import cz.cvut.pmassistant.github.http.RetryExecutor;

class InstallationTokenProviderTest {

	private static final Instant START = Instant.parse("2026-11-21T12:00:00Z");

	@RegisterExtension
	static WireMockExtension github = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	/** A clock the test can move forward. */
	private static final class MutableClock extends Clock {

		private volatile Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private final MutableClock clock = new MutableClock(START);

	private InstallationTokenProvider provider() {
		GitHubHttp appHttp = new GitHubHttp(github.baseUrl(), "2026-03-10", () -> "app-jwt", clock,
				RetryExecutor.noRetry());
		return new InstallationTokenProvider(appHttp, clock);
	}

	private ResponseDefinitionBuilder tokenResponse(String token, Duration validFor) {
		return aResponse().withStatus(201).withHeader("Content-Type", "application/json")
				.withBody("{\"token\":\"" + token + "\",\"expires_at\":\"" + START.plus(validFor)
						+ "\",\"permissions\":{\"issues\":\"read\"},\"repository_selection\":\"selected\"}");
	}

	private void stubToken(long installationId, String token) {
		github.stubFor(post(urlEqualTo("/app/installations/" + installationId + "/access_tokens"))
				.willReturn(tokenResponse(token, Duration.ofHours(1))));
	}

	private static void verifyRequests(int count, long installationId) {
		github.verify(count, postRequestedFor(urlEqualTo("/app/installations/" + installationId + "/access_tokens")));
	}

	@Test
	void requestsATokenAuthenticatedWithTheAppJwt() {
		stubToken(11, "ghs_one");

		assertThat(provider().token(11)).isEqualTo("ghs_one");

		github.verify(postRequestedFor(urlEqualTo("/app/installations/11/access_tokens"))
				.withHeader("Authorization", equalTo("Bearer app-jwt")));
	}

	@Test
	void secondCallUsesTheCacheWithoutAskingGitHub() {
		stubToken(11, "ghs_one");
		InstallationTokenProvider provider = provider();

		assertThat(provider.token(11)).isEqualTo("ghs_one");
		assertThat(provider.token(11)).isEqualTo("ghs_one");

		verifyRequests(1, 11);
	}

	@Test
	void cachesSeparatelyPerInstallation() {
		stubToken(11, "ghs_eleven");
		stubToken(22, "ghs_twenty_two");
		InstallationTokenProvider provider = provider();

		assertThat(provider.token(11)).isEqualTo("ghs_eleven");
		assertThat(provider.token(22)).isEqualTo("ghs_twenty_two");
		assertThat(provider.token(11)).isEqualTo("ghs_eleven");

		verifyRequests(1, 11);
		verifyRequests(1, 22);
	}

	@Test
	void refreshesFiveMinutesBeforeExpiry() {
		github.stubFor(post(urlEqualTo("/app/installations/11/access_tokens")).inScenario("rotation")
				.whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
				.willReturn(tokenResponse("ghs_old", Duration.ofHours(1))).willSetStateTo("rotated"));
		github.stubFor(post(urlEqualTo("/app/installations/11/access_tokens")).inScenario("rotation")
				.whenScenarioStateIs("rotated").willReturn(tokenResponse("ghs_new", Duration.ofHours(2))));
		InstallationTokenProvider provider = provider();

		assertThat(provider.token(11)).isEqualTo("ghs_old");

		clock.advance(Duration.ofMinutes(54).plusSeconds(59));
		assertThat(provider.token(11)).as("one second before the margin").isEqualTo("ghs_old");

		clock.advance(Duration.ofSeconds(1));
		assertThat(provider.token(11)).as("margin reached").isEqualTo("ghs_new");

		verifyRequests(2, 11);
	}

	@Test
	void concurrentCallersShareOneRequest() throws Exception {
		github.stubFor(post(urlEqualTo("/app/installations/11/access_tokens"))
				.willReturn(tokenResponse("ghs_shared", Duration.ofHours(1)).withFixedDelay(300)));
		InstallationTokenProvider provider = provider();
		int threads = 20;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<String>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return provider.token(11);
				}));
			}
			start.countDown();
			for (Future<String> result : results) {
				assertThat(result.get()).isEqualTo("ghs_shared");
			}
		}
		finally {
			pool.shutdownNow();
		}

		verifyRequests(1, 11);
	}

	@Test
	void differentInstallationsDoNotWaitForEachOther() throws Exception {
		github.stubFor(post(urlEqualTo("/app/installations/11/access_tokens"))
				.willReturn(tokenResponse("ghs_slow", Duration.ofHours(1)).withFixedDelay(1500)));
		stubToken(22, "ghs_fast");
		InstallationTokenProvider provider = provider();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<String> slow = pool.submit(() -> provider.token(11));
			Thread.sleep(200);

			long begin = System.nanoTime();
			assertThat(provider.token(22)).isEqualTo("ghs_fast");
			Duration took = Duration.ofNanos(System.nanoTime() - begin);

			assertThat(took).isLessThan(Duration.ofMillis(1000));
			assertThat(slow.get()).isEqualTo("ghs_slow");
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void narrowsTheTokenAndCachesItApartFromTheFullOne() {
		// WireMock prefers the stub added last, so the general one goes first.
		stubToken(11, "ghs_full");
		github.stubFor(post(urlEqualTo("/app/installations/11/access_tokens"))
				.withRequestBody(equalToJson("{\"permissions\":{\"issues\":\"read\"},\"repository_ids\":[42]}"))
				.willReturn(tokenResponse("ghs_read_only", Duration.ofHours(1))));
		InstallationTokenProvider provider = provider();
		TokenScope readOnly = new TokenScope(Map.of("issues", "read"), Set.of(42L));

		assertThat(provider.token(11, readOnly)).isEqualTo("ghs_read_only");
		assertThat(provider.token(11)).isEqualTo("ghs_full");
		assertThat(provider.token(11, new TokenScope(Map.of("issues", "read"), Set.of(42L))))
				.as("an equal scope hits the cache").isEqualTo("ghs_read_only");

		verifyRequests(2, 11);
	}

	@Test
	void sendsNoBodyForAFullTokenAndOnlyPermissionsWhenNoRepositoriesAreGiven() {
		github.stubFor(post(urlEqualTo("/app/installations/11/access_tokens"))
				.willReturn(tokenResponse("ghs_x", Duration.ofHours(1))));
		InstallationTokenProvider provider = provider();

		provider.token(11);
		provider.token(11, TokenScope.ofPermissions(Map.of("issues", "write")));

		github.verify(postRequestedFor(urlEqualTo("/app/installations/11/access_tokens"))
				.withRequestBody(equalToJson("{\"permissions\":{\"issues\":\"write\"}}")));
		assertThat(github.getAllServeEvents()).extracting(e -> e.getRequest().getBodyAsString())
				.contains("");
	}

	@Test
	void aFailedRequestIsNotCachedAndTheNextCallTriesAgain() {
		github.stubFor(post(urlEqualTo("/app/installations/11/access_tokens")).inScenario("flaky")
				.whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
				.willReturn(aResponse().withStatus(404).withBody("{\"message\":\"Not Found\"}"))
				.willSetStateTo("fixed"));
		github.stubFor(post(urlEqualTo("/app/installations/11/access_tokens")).inScenario("flaky")
				.whenScenarioStateIs("fixed").willReturn(tokenResponse("ghs_ok", Duration.ofHours(1))));
		InstallationTokenProvider provider = provider();

		assertThatThrownBy(() -> provider.token(11)).isInstanceOf(GitHubApiException.NotFound.class);
		assertThat(provider.token(11)).isEqualTo("ghs_ok");
	}

	@Test
	void invalidateForcesANewRequestForEveryScopeOfThatInstallationOnly() {
		stubToken(11, "ghs_eleven");
		stubToken(22, "ghs_twenty_two");
		InstallationTokenProvider provider = provider();
		provider.token(11);
		provider.token(22);

		provider.invalidate(11);
		provider.token(11);
		provider.token(22);

		verifyRequests(2, 11);
		verifyRequests(1, 22);
	}

	@Test
	void tokenSourceAsksTheProviderOnEveryCall() {
		stubToken(11, "ghs_one");

		assertThat(provider().tokenSource(11, TokenScope.FULL).token()).isEqualTo("ghs_one");
	}

	@Test
	void rejectsAnIncompleteResponse() {
		github.stubFor(post(urlEqualTo("/app/installations/11/access_tokens")).willReturn(aResponse()
				.withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"token\":\"ghs_x\"}")));

		assertThatThrownBy(() -> provider().token(11)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void theTokenNeverAppearsInToStringOrExceptionMessages() {
		InstallationToken token = new InstallationToken("ghs_secret_value", START);

		assertThat(token.toString()).doesNotContain("ghs_secret_value");
	}
}
