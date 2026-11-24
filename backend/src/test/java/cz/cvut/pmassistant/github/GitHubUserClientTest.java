package cz.cvut.pmassistant.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import cz.cvut.pmassistant.github.http.GitHubApiException;
import cz.cvut.pmassistant.github.http.GitHubHttp;
import cz.cvut.pmassistant.github.http.RetryExecutor;
import cz.cvut.pmassistant.github.model.Installation;
import cz.cvut.pmassistant.github.model.Repository;

class GitHubUserClientTest {

	@RegisterExtension
	static WireMockExtension github = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private GitHubUserClient client() {
		GitHubHttp http = new GitHubHttp(github.baseUrl(), "2026-03-10", () -> "user-oauth-token",
				Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), RetryExecutor.noRetry());
		return new GitHubUserClient(http);
	}

	private static ResponseDefinitionBuilder json(String fixture) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
				.withBody(Fixtures.read(fixture));
	}

	@Test
	void listsTheInstallationsTheUserCanAccessWithTheUsersToken() {
		github.stubFor(get(urlPathEqualTo("/user/installations")).willReturn(json("user-installations.json")));

		List<Installation> installations = client().listInstallations();

		assertThat(installations).extracting(Installation::id).containsExactly(1001L, 1002L);
		assertThat(installations.get(0).account().login()).isEqualTo("cvut-team");
		github.verify(getRequestedFor(urlPathEqualTo("/user/installations"))
				.withHeader("Authorization", equalTo("Bearer user-oauth-token"))
				.withQueryParam("per_page", equalTo("100")));
	}

	@Test
	void listsRepositoriesOfAnInstallationAcrossWrappedPages() {
		github.stubFor(get(urlPathEqualTo("/user/installations/1001/repositories"))
				.willReturn(json("user-installation-repos-page1.json").withHeader("Link", "<" + github.baseUrl()
						+ "/user/installations/1001/repositories?per_page=100&page=2>; rel=\"next\"")));
		github.stubFor(get(urlPathEqualTo("/user/installations/1001/repositories"))
				.withQueryParam("page", equalTo("2"))
				.willReturn(json("user-installation-repos-page2.json")));

		List<Repository> repositories = client().listInstallationRepositories(1001);

		assertThat(repositories).extracting(Repository::id).containsExactly(4242L, 4243L, 4244L);
		assertThat(repositories).extracting(Repository::fullName)
				.containsExactly("cvut-team/pm-assistant", "cvut-team/infrastructure", "cvut-team/docs");
		assertThat(repositories.get(0).owner().login()).isEqualTo("cvut-team");
		assertThat(repositories.get(1).isPrivate()).isTrue();
	}

	@Test
	void tellsWhichRepositoriesTheUserMayMaintain() {
		github.stubFor(get(urlPathEqualTo("/user/installations/1001/repositories"))
				.willReturn(json("user-installation-repos-page1.json")));

		List<Repository> repositories = client().listInstallationRepositories(1001);

		assertThat(repositories.get(0).isMaintainedByUser()).as("admin and maintain").isTrue();
		assertThat(repositories.get(1).isMaintainedByUser()).as("push only").isFalse();
	}

	@Test
	void missingPermissionFlagsCountAsNoRights() {
		github.stubFor(get(urlPathEqualTo("/user/installations/1001/repositories"))
				.willReturn(json("user-installation-repos-page2.json")));

		Repository docs = client().listInstallationRepositories(1001).get(0);

		assertThat(docs.permissions().maintain()).isNull();
		assertThat(docs.isMaintainedByUser()).isFalse();
	}

	@Test
	void anExpiredUserTokenIsReportedAsUnauthorized() {
		github.stubFor(get(urlPathEqualTo("/user/installations"))
				.willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Bad credentials\"}")));

		assertThatThrownBy(() -> client().listInstallations()).isInstanceOf(GitHubApiException.Unauthorized.class);
	}
}
