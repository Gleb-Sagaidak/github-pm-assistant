package cz.cvut.pmassistant.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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
import cz.cvut.pmassistant.github.model.IssueLink;
import cz.cvut.pmassistant.github.model.IssueState;
import cz.cvut.pmassistant.github.model.RepoRef;

/** Dependencies (blocked by / blocking) and sub-issues. */
class RestGitHubReaderLinksTest {

	private static final RepoRef REPO = new RepoRef(4242, "cvut-team", "pm-assistant");

	@RegisterExtension
	static WireMockExtension github = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private RestGitHubReader reader() {
		GitHubHttp http = new GitHubHttp(github.baseUrl(), "2026-03-10", () -> "tok",
				Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), RetryExecutor.noRetry());
		return new RestGitHubReader(http);
	}

	private static ResponseDefinitionBuilder json(String fixture) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
				.withBody(Fixtures.read(fixture));
	}

	@Test
	void listsIssuesThatBlockAnIssue() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues/7/dependencies/blocked_by"))
				.willReturn(json("blocked-by.json")));

		List<IssueLink> blockers = reader().listBlockedBy(REPO, 7);

		assertThat(blockers).singleElement().satisfies(blocker -> {
			assertThat(blocker.number()).isEqualTo(12);
			assertThat(blocker.id()).isEqualTo(1000000012L);
			assertThat(blocker.title()).isEqualTo("Migrate session storage");
			assertThat(blocker.state()).isEqualTo(IssueState.OPEN);
			assertThat(blocker.repositoryUrl()).endsWith("/repos/cvut-team/pm-assistant");
		});
	}

	@Test
	void listsIssuesBlockedByAnIssueIncludingOtherRepositories() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues/7/dependencies/blocking"))
				.willReturn(json("blocking.json")));

		List<IssueLink> blocked = reader().listBlocking(REPO, 7);

		assertThat(blocked).extracting(IssueLink::number).containsExactly(15, 3);
		assertThat(blocked.get(1).repositoryUrl()).endsWith("/repos/cvut-team/infrastructure");
		assertThat(blocked.get(1).state()).isEqualTo(IssueState.CLOSED);
	}

	@Test
	void listsSubIssues() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues/20/sub_issues"))
				.willReturn(json("sub-issues.json")));

		List<IssueLink> subIssues = reader().listSubIssues(REPO, 20);

		assertThat(subIssues).extracting(IssueLink::number).containsExactly(21, 22);
		assertThat(subIssues).extracting(IssueLink::state).containsExactly(IssueState.OPEN, IssueState.CLOSED);
	}

	@Test
	void anIssueWithoutLinksGivesAnEmptyList() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues/30/dependencies/blocked_by"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("[]")));

		assertThat(reader().listBlockedBy(REPO, 30)).isEmpty();
	}

	@Test
	void repositoriesWithoutTheFeatureAreReportedAsNotFound() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues/7/sub_issues"))
				.willReturn(aResponse().withStatus(404).withBody("{\"message\":\"Not Found\"}")));

		assertThatThrownBy(() -> reader().listSubIssues(REPO, 7)).isInstanceOf(GitHubApiException.NotFound.class);
	}
}
