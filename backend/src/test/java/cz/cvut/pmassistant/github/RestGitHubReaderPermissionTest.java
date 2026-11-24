package cz.cvut.pmassistant.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import cz.cvut.pmassistant.github.http.GitHubApiException;
import cz.cvut.pmassistant.github.http.GitHubHttp;
import cz.cvut.pmassistant.github.http.RetryExecutor;
import cz.cvut.pmassistant.github.model.CollaboratorPermission;
import cz.cvut.pmassistant.github.model.RepoRef;
import cz.cvut.pmassistant.github.model.RepoRole;

class RestGitHubReaderPermissionTest {

	private static final RepoRef REPO = new RepoRef(4242, "cvut-team", "pm-assistant");

	@RegisterExtension
	static WireMockExtension github = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private RestGitHubReader reader() {
		GitHubHttp http = new GitHubHttp(github.baseUrl(), "2026-03-10", () -> "installation-token",
				Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), RetryExecutor.noRetry());
		return new RestGitHubReader(http);
	}

	@Test
	void asksForThePermissionWithTheInstallationToken() {
		github.stubFor(get(urlEqualTo("/repos/cvut-team/pm-assistant/collaborators/bob/permission"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody(Fixtures.read("permission-triage.json"))));

		CollaboratorPermission permission = reader().getCollaboratorPermission(REPO, "bob");

		assertThat(permission.hasAtLeast(RepoRole.TRIAGE)).isTrue();
		assertThat(permission.user().login()).isEqualTo("bob");
		github.verify(getRequestedFor(urlEqualTo("/repos/cvut-team/pm-assistant/collaborators/bob/permission"))
				.withHeader("Authorization", equalTo("Bearer installation-token")));
	}

	@Test
	void anUnknownUserIsNotFound() {
		github.stubFor(get(urlEqualTo("/repos/cvut-team/pm-assistant/collaborators/ghost/permission"))
				.willReturn(aResponse().withStatus(404).withBody("{\"message\":\"Not Found\"}")));

		assertThatThrownBy(() -> reader().getCollaboratorPermission(REPO, "ghost"))
				.isInstanceOf(GitHubApiException.NotFound.class);
	}

	@Test
	void refusesLoginsThatCouldChangeTheRequestPath() {
		assertThatThrownBy(() -> reader().getCollaboratorPermission(REPO, "bob/../../admin"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> reader().getCollaboratorPermission(REPO, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(github.getAllServeEvents()).isEmpty();
	}
}
