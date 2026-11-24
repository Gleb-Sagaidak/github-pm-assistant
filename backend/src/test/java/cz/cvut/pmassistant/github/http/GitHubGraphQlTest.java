package cz.cvut.pmassistant.github.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import tools.jackson.databind.JsonNode;

/** GraphQL reports failures inside a 200 response; they must surface as exceptions. */
class GitHubGraphQlTest {

	@RegisterExtension
	static WireMockExtension github = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private GitHubGraphQl graphQl() {
		GitHubHttp http = new GitHubHttp(github.baseUrl(), "2026-03-10", () -> "tok",
				Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), RetryExecutor.noRetry());
		return new GitHubGraphQl(http);
	}

	private static ResponseDefinitionBuilder ok(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
				.withHeader("x-github-request-id", "GQL-1").withBody(body);
	}

	private void stub(String body) {
		github.stubFor(post(urlEqualTo("/graphql")).willReturn(ok(body)));
	}

	@Test
	void sendsQueryAndVariablesAsJsonAndReturnsTheDataObject() {
		stub("{\"data\":{\"viewer\":{\"login\":\"app\"}}}");
		Map<String, Object> variables = new HashMap<>();
		variables.put("login", "cvut-team");
		variables.put("cursor", null);

		JsonNode data = graphQl().query("query($login: String!) { viewer { login } }", variables);

		assertThat(data.path("viewer").path("login").asString()).isEqualTo("app");
		github.verify(postRequestedFor(urlEqualTo("/graphql"))
				.withHeader("Authorization", equalTo("Bearer tok"))
				.withHeader("Content-Type", equalTo("application/json"))
				.withRequestBody(equalToJson("""
						{"query": "query($login: String!) { viewer { login } }",
						 "variables": {"login": "cvut-team", "cursor": null}}""")));
	}

	@Test
	void notFoundErrorBecomesNotFound() {
		stub("{\"data\":null,\"errors\":[{\"type\":\"NOT_FOUND\",\"message\":\"Could not resolve\"}]}");

		assertThatThrownBy(() -> graphQl().query("q", Map.of()))
				.isInstanceOfSatisfying(GitHubApiException.NotFound.class, e -> {
					assertThat(e.getMessage()).contains("Could not resolve");
					assertThat(e.requestId()).isEqualTo("GQL-1");
					assertThat(e.path()).isEqualTo("/graphql");
				});
	}

	@Test
	void forbiddenAndMissingScopesBecomeForbidden() {
		stub("{\"data\":null,\"errors\":[{\"type\":\"FORBIDDEN\",\"message\":\"Resource not accessible\"}]}");
		assertThatThrownBy(() -> graphQl().query("q", Map.of())).isInstanceOf(GitHubApiException.Forbidden.class);

		stub("{\"data\":null,\"errors\":[{\"type\":\"INSUFFICIENT_SCOPES\",\"message\":\"needs read:project\"}]}");
		assertThatThrownBy(() -> graphQl().query("q", Map.of())).isInstanceOf(GitHubApiException.Forbidden.class);
	}

	@Test
	void rateLimitErrorBecomesRateLimited() {
		stub("{\"data\":null,\"errors\":[{\"type\":\"RATE_LIMITED\",\"message\":\"API rate limit exceeded\"}]}");

		assertThatThrownBy(() -> graphQl().query("q", Map.of()))
				.isInstanceOf(GitHubApiException.RateLimited.class)
				.hasMessageContaining("rate limit");
	}

	@Test
	void unknownErrorTypeBecomesTheBaseException() {
		stub("{\"data\":null,\"errors\":[{\"type\":\"SOMETHING_NEW\",\"message\":\"odd\"}]}");

		assertThatThrownBy(() -> graphQl().query("q", Map.of()))
				.isExactlyInstanceOf(GitHubApiException.class)
				.hasMessageContaining("odd");
	}

	@Test
	void errorWithoutTypeAndSeveralMessagesAreAllReported() {
		stub("{\"errors\":[{\"message\":\"first problem\"},{\"message\":\"second problem\"}]}");

		assertThatThrownBy(() -> graphQl().query("q", Map.of()))
				.isExactlyInstanceOf(GitHubApiException.class)
				.hasMessageContaining("first problem; second problem");
	}

	@Test
	void partialDataIsNotUsedWhenThereAreErrors() {
		stub("{\"data\":{\"organization\":{\"projectV2\":null}},"
				+ "\"errors\":[{\"type\":\"NOT_FOUND\",\"message\":\"no such project\"}]}");

		assertThatThrownBy(() -> graphQl().query("q", Map.of())).isInstanceOf(GitHubApiException.NotFound.class);
	}

	@Test
	void anEmptyErrorsArrayIsFine() {
		stub("{\"data\":{\"ok\":true},\"errors\":[]}");

		assertThat(graphQl().query("q", Map.of()).path("ok").asBoolean()).isTrue();
	}

	@Test
	void aResponseWithoutDataIsAnError() {
		stub("{}");

		assertThatThrownBy(() -> graphQl().query("q", Map.of()))
				.isExactlyInstanceOf(GitHubApiException.class)
				.hasMessageContaining("no data");
	}

	@Test
	void httpErrorsStillUseTheRestMapping() {
		github.stubFor(post(urlEqualTo("/graphql")).willReturn(aResponse().withStatus(401)
				.withBody("{\"message\":\"Bad credentials\"}")));

		assertThatThrownBy(() -> graphQl().query("q", Map.of())).isInstanceOf(GitHubApiException.Unauthorized.class);
	}

	@Test
	void queryVariablesReachGitHubUnchanged() {
		stub("{\"data\":{}}");

		graphQl().query("q", Map.of("number", 3));

		github.verify(postRequestedFor(urlEqualTo("/graphql"))
				.withRequestBody(matchingJsonPath("$.variables.number", equalTo("3"))));
	}
}
