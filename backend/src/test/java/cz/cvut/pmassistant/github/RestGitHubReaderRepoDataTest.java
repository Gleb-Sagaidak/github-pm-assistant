package cz.cvut.pmassistant.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import cz.cvut.pmassistant.github.http.GitHubHttp;
import cz.cvut.pmassistant.github.http.RetryExecutor;
import cz.cvut.pmassistant.github.model.Label;
import cz.cvut.pmassistant.github.model.RepoRef;
import cz.cvut.pmassistant.github.model.TimelineEvent;
import cz.cvut.pmassistant.github.model.User;

/** Timeline, labels and collaborators of a repository. */
class RestGitHubReaderRepoDataTest {

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
	void readsTimelineEventsOfEveryKind() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues/7/timeline"))
				.willReturn(json("timeline.json")));

		List<TimelineEvent> events = reader().getTimeline(REPO, 7);

		assertThat(events).extracting(TimelineEvent::event)
				.containsExactly("labeled", "assigned", "cross-referenced", "commented", "committed", "reviewed");

		TimelineEvent labeled = events.get(0);
		assertThat(labeled.actor().login()).isEqualTo("alice");
		assertThat(labeled.label().name()).isEqualTo("bug");
		assertThat(labeled.createdAt()).isEqualTo(Instant.parse("2026-11-01T10:05:00Z"));

		assertThat(events.get(1).assignee().login()).isEqualTo("bob");

		TimelineEvent crossReference = events.get(2);
		assertThat(crossReference.id()).isNull();
		assertThat(crossReference.source().issue().number()).isEqualTo(8);
		assertThat(crossReference.source().issue().repositoryUrl()).endsWith("/cvut-team/pm-assistant");

		TimelineEvent commented = events.get(3);
		assertThat(commented.body()).isEqualTo("Reproduced on main.");
		assertThat(commented.actor().login()).isEqualTo("bob");
	}

	@Test
	void eventsWithoutCreatedAtAreStillReadable() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues/7/timeline"))
				.willReturn(json("timeline.json")));

		List<TimelineEvent> events = reader().getTimeline(REPO, 7);

		TimelineEvent committed = events.get(4);
		TimelineEvent reviewed = events.get(5);
		assertThat(committed.createdAt()).isNull();
		assertThat(committed.actor()).isNull();
		assertThat(reviewed.createdAt()).isNull();
		assertThat(reviewed.id()).isEqualTo(9000009L);
	}

	@Test
	void requestsTheTimelineWithPageSize() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues/7/timeline"))
				.willReturn(json("timeline.json")));

		reader().getTimeline(REPO, 7);

		github.verify(getRequestedFor(urlPathEqualTo("/repos/cvut-team/pm-assistant/issues/7/timeline"))
				.withQueryParam("per_page", equalTo("100")));
	}

	@Test
	void listsLabelsWithNumericIdsAndOptionalDescription() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/labels")).willReturn(json("labels.json")));

		List<Label> labels = reader().listLabels(REPO);

		assertThat(labels).extracting(Label::name).containsExactly("bug", "area: backend", "priority: high");
		assertThat(labels).extracting(Label::id).containsExactly(5500001L, 5500002L, 5500003L);
		assertThat(labels.get(0).description()).isEqualTo("Something isn't working");
		assertThat(labels.get(1).description()).isNull();
		assertThat(labels.get(2).color()).isEqualTo("b60205");
	}

	@Test
	void listsLabelsAcrossPages() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/labels"))
				.willReturn(json("labels.json").withHeader("Link", "<" + github.baseUrl()
						+ "/repos/cvut-team/pm-assistant/labels?per_page=100&page=2>; rel=\"next\"")));
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/labels"))
				.withQueryParam("page", equalTo("2"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("[{\"id\":5500004,\"name\":\"docs\",\"color\":\"0075ca\",\"description\":null}]")));

		assertThat(reader().listLabels(REPO)).extracting(Label::id)
				.containsExactly(5500001L, 5500002L, 5500003L, 5500004L);
	}

	@Test
	void listsCollaborators() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/collaborators"))
				.willReturn(json("collaborators.json")));

		List<User> collaborators = reader().listCollaborators(REPO);

		assertThat(collaborators).extracting(User::login).containsExactly("alice", "bob");
		assertThat(collaborators.get(1).id()).isEqualTo(889L);
	}
}
