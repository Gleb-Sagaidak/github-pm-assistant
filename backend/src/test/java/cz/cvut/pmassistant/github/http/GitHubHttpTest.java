package cz.cvut.pmassistant.github.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import cz.cvut.pmassistant.github.model.Label;

class GitHubHttpTest {

	private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

	@RegisterExtension
	static WireMockExtension github = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private GitHubHttp http(BearerTokenSource tokens) {
		return new GitHubHttp(github.baseUrl(), "2026-03-10", tokens, Clock.fixed(NOW, ZoneOffset.UTC),
				RetryExecutor.noRetry());
	}

	private GitHubHttp http() {
		return http(() -> "tok-1");
	}

	@Test
	void sendsStandardHeadersOnEveryRequest() {
		github.stubFor(get("/repos/o/r/labels").willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody("[]")));

		http().get("/repos/o/r/labels", new ParameterizedTypeReference<List<Label>>() {
		});

		github.verify(getRequestedFor(urlEqualTo("/repos/o/r/labels"))
				.withHeader("Authorization", equalTo("Bearer tok-1"))
				.withHeader("Accept", equalTo("application/vnd.github+json"))
				.withHeader("X-GitHub-Api-Version", equalTo("2026-03-10"))
				.withHeader("User-Agent", equalTo("github-pm-assistant")));
	}

	@Test
	void asksTheTokenSourceForEveryRequest() {
		github.stubFor(get("/x").willReturn(aResponse().withStatus(200).withBody("{}")));
		AtomicInteger counter = new AtomicInteger();
		GitHubHttp http = http(() -> "tok-" + counter.incrementAndGet());

		http.get("/x", String.class);
		http.get("/x", String.class);

		github.verify(getRequestedFor(urlEqualTo("/x")).withHeader("Authorization", equalTo("Bearer tok-1")));
		github.verify(getRequestedFor(urlEqualTo("/x")).withHeader("Authorization", equalTo("Bearer tok-2")));
	}

	@Test
	void parsesBodyAndExposesHeaders() {
		github.stubFor(get("/repos/o/r/labels").willReturn(aResponse().withStatus(200)
				.withHeader("Link", "<https://api.github.com/x?page=2>; rel=\"next\"")
				.withHeader("Content-Type", "application/json")
				.withBody("[{\"id\":5,\"name\":\"bug\",\"color\":\"d73a4a\",\"description\":null}]")));

		GitHubResponse<List<Label>> response = http().exchange(HttpMethod.GET, "/repos/o/r/labels", null,
				new ParameterizedTypeReference<List<Label>>() {
				});

		assertThat(response.body()).containsExactly(new Label(5, "bug", "d73a4a", null));
		assertThat(response.headers().getFirst("Link")).contains("rel=\"next\"");
	}

	@Test
	void postsJsonBody() {
		github.stubFor(post("/graphql").willReturn(aResponse().withStatus(200).withBody("{\"data\":{}}")));

		http().post("/graphql", java.util.Map.of("query", "{ viewer { login } }"), String.class);

		github.verify(postRequestedFor(urlEqualTo("/graphql"))
				.withHeader("Content-Type", equalTo("application/json"))
				.withRequestBody(equalToJson("{\"query\":\"{ viewer { login } }\"}")));
	}

	@Test
	void mapsUnauthorizedAndKeepsRequestDetails() {
		github.stubFor(get("/app").willReturn(aResponse().withStatus(401)
				.withHeader("x-github-request-id", "REQ-401")
				.withBody("{\"message\":\"Bad credentials\"}")));

		assertThatThrownBy(() -> http().get("/app", String.class))
				.isInstanceOfSatisfying(GitHubApiException.Unauthorized.class, e -> {
					assertThat(e.status()).isEqualTo(401);
					assertThat(e.method()).isEqualTo("GET");
					assertThat(e.path()).isEqualTo("/app");
					assertThat(e.requestId()).isEqualTo("REQ-401");
					assertThat(e.getMessage()).contains("Bad credentials");
				});
	}

	@Test
	void mapsNotFound() {
		github.stubFor(get("/repos/o/r/issues/9").willReturn(aResponse().withStatus(404)
				.withBody("{\"message\":\"Not Found\"}")));

		assertThatThrownBy(() -> http().get("/repos/o/r/issues/9", String.class))
				.isInstanceOf(GitHubApiException.NotFound.class);
	}

	@Test
	void mapsPlain403ToForbidden() {
		github.stubFor(get("/x").willReturn(aResponse().withStatus(403)
				.withHeader("x-ratelimit-remaining", "4999")
				.withBody("{\"message\":\"Resource not accessible by integration\"}")));

		assertThatThrownBy(() -> http().get("/x", String.class))
				.isInstanceOf(GitHubApiException.Forbidden.class)
				.hasMessageContaining("Resource not accessible");
	}

	@Test
	void maps403WithExhaustedQuotaToRateLimitedWaitingUntilReset() {
		long reset = NOW.plusSeconds(42).getEpochSecond();
		github.stubFor(get("/x").willReturn(aResponse().withStatus(403)
				.withHeader("x-ratelimit-remaining", "0")
				.withHeader("x-ratelimit-reset", String.valueOf(reset))
				.withBody("{\"message\":\"API rate limit exceeded\"}")));

		assertThatThrownBy(() -> http().get("/x", String.class))
				.isInstanceOfSatisfying(GitHubApiException.RateLimited.class,
						e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(42)));
	}

	@Test
	void maps403SecondaryRateLimitByMessage() {
		github.stubFor(get("/x").willReturn(aResponse().withStatus(403)
				.withBody("{\"message\":\"You have exceeded a secondary rate limit. Please wait.\"}")));

		assertThatThrownBy(() -> http().get("/x", String.class))
				.isInstanceOfSatisfying(GitHubApiException.RateLimited.class,
						e -> assertThat(e.retryAfter()).isNull());
	}

	@Test
	void maps429WithRetryAfter() {
		github.stubFor(get("/x").willReturn(aResponse().withStatus(429).withHeader("retry-after", "3")));

		assertThatThrownBy(() -> http().get("/x", String.class))
				.isInstanceOfSatisfying(GitHubApiException.RateLimited.class, e -> {
					assertThat(e.status()).isEqualTo(429);
					assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(3));
				});
	}

	@Test
	void mapsServerErrorsToTransientWithoutRetrying() {
		github.stubFor(get("/x").willReturn(aResponse().withStatus(503).withBody("<html>unavailable</html>")));

		assertThatThrownBy(() -> http().get("/x", String.class))
				.isInstanceOfSatisfying(GitHubApiException.Transient.class, e -> {
					assertThat(e.status()).isEqualTo(503);
					assertThat(e.getMessage()).contains("unavailable");
				});
		github.verify(1, getRequestedFor(urlEqualTo("/x")));
	}

	@Test
	void mapsNetworkFailureToTransientWithoutStatus() {
		github.stubFor(get("/x").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

		assertThatThrownBy(() -> http().get("/x", String.class))
				.isInstanceOfSatisfying(GitHubApiException.Transient.class, e -> {
					assertThat(e.status()).isZero();
					assertThat(e.requestId()).isNull();
					assertThat(e.getCause()).isNotNull();
				});
	}

	@Test
	void mapsValidationErrorToBaseException() {
		github.stubFor(post("/x").willReturn(aResponse().withStatus(422)
				.withBody("{\"message\":\"Validation Failed\"}")));

		assertThatThrownBy(() -> http().post("/x", java.util.Map.of("a", 1), String.class))
				.isExactlyInstanceOf(GitHubApiException.class)
				.satisfies(e -> assertThat(((GitHubApiException) e).status()).isEqualTo(422));
	}

	@Test
	void doesNotFollowRedirects() {
		github.stubFor(get("/old").willReturn(aResponse().withStatus(301)
				.withHeader("Location", "https://elsewhere.example/new")));

		assertThatThrownBy(() -> http().get("/old", String.class))
				.isExactlyInstanceOf(GitHubApiException.class)
				.hasMessageContaining("redirect to https://elsewhere.example/new");
		assertThat(github.getAllServeEvents()).hasSize(1);
	}

	@Test
	void rejectsAbsoluteUrlsSoTheTokenCannotLeaveTheConfiguredHost() {
		assertThatThrownBy(() -> http().get("https://evil.example/steal", String.class))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
