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
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import cz.cvut.pmassistant.github.http.GitHubApiException;
import cz.cvut.pmassistant.github.http.GitHubHttp;
import cz.cvut.pmassistant.github.http.RetryExecutor;
import cz.cvut.pmassistant.github.model.Installation;

class GitHubAppClientTest {

	@RegisterExtension
	static WireMockExtension github = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private GitHubAppClient client() {
		GitHubHttp http = new GitHubHttp(github.baseUrl(), "2026-03-10", () -> "app-jwt",
				Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), RetryExecutor.noRetry());
		return new GitHubAppClient(http);
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String fixture) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
				.withBody(Fixtures.read(fixture));
	}

	@Test
	void listsInstallationsAuthenticatedAsTheApp() {
		github.stubFor(get(urlEqualTo("/app/installations?per_page=100")).willReturn(json("installations.json")));

		List<Installation> installations = client().listInstallations();

		assertThat(installations).extracting(Installation::id).containsExactly(1001L, 1002L);
		github.verify(getRequestedFor(urlEqualTo("/app/installations?per_page=100"))
				.withHeader("Authorization", equalTo("Bearer app-jwt")));
	}

	@Test
	void readsAccountPermissionsAndSuspension() {
		github.stubFor(get(urlEqualTo("/app/installations?per_page=100")).willReturn(json("installations.json")));

		List<Installation> installations = client().listInstallations();

		Installation org = installations.get(0);
		assertThat(org.account().login()).isEqualTo("cvut-team");
		assertThat(org.isOrganization()).isTrue();
		assertThat(org.repositorySelection()).isEqualTo("selected");
		assertThat(org.permissions()).containsEntry("issues", "write").containsEntry("organization_projects", "write");
		assertThat(org.isSuspended()).isFalse();

		Installation user = installations.get(1);
		assertThat(user.isOrganization()).isFalse();
		assertThat(user.isSuspended()).isTrue();
		assertThat(user.suspendedAt()).isEqualTo(Instant.parse("2026-11-22T07:00:00Z"));
		assertThat(user.permissions()).doesNotContainKey("organization_projects");
	}

	@Test
	void findsTheInstallationOfARepository() {
		github.stubFor(get(urlEqualTo("/repos/cvut-team/pm-assistant/installation"))
				.willReturn(json("repo-installation.json")));

		Optional<Installation> found = client().findInstallationForRepo("cvut-team", "pm-assistant");

		assertThat(found).hasValueSatisfying(i -> assertThat(i.id()).isEqualTo(1001L));
		github.verify(getRequestedFor(urlEqualTo("/repos/cvut-team/pm-assistant/installation"))
				.withHeader("Authorization", equalTo("Bearer app-jwt")));
	}

	@Test
	void answersEmptyWhenTheAppIsNotInstalledOnTheRepository() {
		github.stubFor(get(urlEqualTo("/repos/someone/else/installation"))
				.willReturn(aResponse().withStatus(404).withBody("{\"message\":\"Not Found\"}")));

		assertThat(client().findInstallationForRepo("someone", "else")).isEmpty();
	}

	@Test
	void otherErrorsAreNotHiddenBehindEmpty() {
		github.stubFor(get(urlEqualTo("/repos/o/r/installation"))
				.willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Bad credentials\"}")));

		assertThatThrownBy(() -> client().findInstallationForRepo("o", "r"))
				.isInstanceOf(GitHubApiException.Unauthorized.class);
	}
}
