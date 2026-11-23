package cz.cvut.pmassistant.github;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
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
import cz.cvut.pmassistant.github.model.Comment;
import cz.cvut.pmassistant.github.model.Issue;
import cz.cvut.pmassistant.github.model.IssueState;
import cz.cvut.pmassistant.github.model.IssueStateFilter;
import cz.cvut.pmassistant.github.model.RepoRef;

class RestGitHubReaderIssuesTest {

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
	void readsAnIssueWithAllItsFields() {
		github.stubFor(get("/repos/cvut-team/pm-assistant/issues/7").willReturn(json("issue.json")));

		Issue issue = reader().getIssue(REPO, 7);

		assertThat(issue.number()).isEqualTo(7);
		assertThat(issue.title()).isEqualTo("Login fails with expired session");
		assertThat(issue.state()).isEqualTo(IssueState.OPEN);
		assertThat(issue.user().login()).isEqualTo("alice");
		assertThat(issue.labels()).extracting(l -> l.name()).containsExactly("bug", "area: backend");
		assertThat(issue.labels().get(0).id()).isEqualTo(5500001L);
		assertThat(issue.assignees()).extracting(u -> u.login()).containsExactly("bob");
		assertThat(issue.milestone().title()).isEqualTo("Sprint 3");
		assertThat(issue.comments()).isEqualTo(2);
		assertThat(issue.updatedAt()).isEqualTo(Instant.parse("2026-11-05T14:30:00Z"));
		assertThat(issue.closedAt()).isNull();
		assertThat(issue.isPullRequest()).isFalse();
	}

	@Test
	void reportsAMissingIssueAsNotFound() {
		github.stubFor(get("/repos/cvut-team/pm-assistant/issues/99")
				.willReturn(aResponse().withStatus(404).withBody("{\"message\":\"Not Found\"}")));

		assertThatThrownBy(() -> reader().getIssue(REPO, 99)).isInstanceOf(GitHubApiException.NotFound.class);
	}

	@Test
	void listsIssuesAcrossPagesAndDropsPullRequests() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues"))
				.withQueryParam("page", absent())
				.willReturn(json("issues-page1.json").withHeader("Link", "<" + github.baseUrl()
						+ "/repos/cvut-team/pm-assistant/issues?state=open&per_page=100&page=2>; rel=\"next\"")));
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues"))
				.withQueryParam("page", equalTo("2"))
				.willReturn(json("issues-page2.json")));

		List<Issue> issues = reader().listIssues(REPO, IssueStateFilter.OPEN, null);

		assertThat(issues).extracting(Issue::number).containsExactly(7, 9);
		assertThat(issues).noneMatch(Issue::isPullRequest);
		github.verify(2, getRequestedFor(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues")));
	}

	@Test
	void passesStateAndSinceToGitHub() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues")).willReturn(json("issues-page2.json")));

		reader().listIssues(REPO, IssueStateFilter.ALL, Instant.parse("2026-11-01T00:00:00Z"));

		github.verify(getRequestedFor(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues"))
				.withQueryParam("state", equalTo("all"))
				.withQueryParam("since", equalTo("2026-11-01T00:00:00Z"))
				.withQueryParam("per_page", equalTo("100")));
	}

	@Test
	void omitsSinceWhenNotGiven() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues")).willReturn(json("issues-page2.json")));

		reader().listIssues(REPO, IssueStateFilter.CLOSED, null);

		github.verify(getRequestedFor(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues"))
				.withQueryParam("state", equalTo("closed"))
				.withQueryParam("since", absent()));
	}

	@Test
	void listsCommentsOfAnIssue() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues/7/comments"))
				.willReturn(json("comments.json")));

		List<Comment> comments = reader().listComments(REPO, 7);

		assertThat(comments).extracting(c -> c.user().login()).containsExactly("bob", "alice");
		assertThat(comments.get(0).body()).isEqualTo("Reproduced on main.");
		assertThat(comments.get(1).createdAt()).isEqualTo(Instant.parse("2026-11-05T14:30:00Z"));
	}

	@Test
	void refusesRepositoryNamesThatCouldChangeTheRequestPath() {
		RepoRef evil = new RepoRef(1, "cvut-team", "../../admin");

		assertThatThrownBy(() -> reader().getIssue(evil, 1)).isInstanceOf(IllegalArgumentException.class);
		assertThat(github.getAllServeEvents()).isEmpty();
	}
}
