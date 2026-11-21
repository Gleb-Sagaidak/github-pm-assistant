package cz.cvut.pmassistant.github.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** The records must read GitHub's snake_case JSON and tolerate fields we do not model. */
class ModelJsonTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void readsIssueAndIgnoresUnknownAndRemovedFields() throws Exception {
		String json = """
				{
				  "id": 1001, "number": 7, "title": "Login fails", "body": null, "state": "open",
				  "state_reason": null,
				  "assignee": {"id": 1, "login": "legacy"},
				  "assignees": [{"id": 2, "login": "alice", "avatar_url": "https://a/2", "site_admin": false}],
				  "labels": [{"id": 55, "name": "bug", "color": "d73a4a", "description": null, "default": true}],
				  "milestone": {"number": 3, "title": "v1", "state": "open", "due_on": "2026-12-01T00:00:00Z"},
				  "comments": 4,
				  "created_at": "2026-11-01T10:00:00Z", "updated_at": "2026-11-02T11:30:00Z", "closed_at": null,
				  "html_url": "https://github.com/o/r/issues/7",
				  "reactions": {"total_count": 0}
				}
				""";

		Issue issue = mapper.readValue(json, Issue.class);

		assertThat(issue.number()).isEqualTo(7);
		assertThat(issue.state()).isEqualTo(IssueState.OPEN);
		assertThat(issue.body()).isNull();
		assertThat(issue.assignees()).extracting(User::login).containsExactly("alice");
		assertThat(issue.assignees().get(0).avatarUrl()).isEqualTo("https://a/2");
		assertThat(issue.labels()).singleElement().satisfies(label -> {
			assertThat(label.id()).isEqualTo(55);
			assertThat(label.description()).isNull();
		});
		assertThat(issue.milestone().dueOn()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
		assertThat(issue.updatedAt()).isEqualTo(Instant.parse("2026-11-02T11:30:00Z"));
		assertThat(issue.closedAt()).isNull();
		assertThat(issue.isPullRequest()).isFalse();
	}

	@Test
	void detectsPullRequestInIssueList() throws Exception {
		String json = """
				{"id": 2, "number": 8, "title": "Fix", "state": "closed", "comments": 0,
				 "pull_request": {"url": "https://api.github.com/repos/o/r/pulls/8", "merged_at": null}}
				""";

		Issue issue = mapper.readValue(json, Issue.class);

		assertThat(issue.isPullRequest()).isTrue();
		assertThat(issue.state()).isEqualTo(IssueState.CLOSED);
		assertThat(issue.labels()).isEmpty();
		assertThat(issue.assignees()).isEmpty();
	}

	@Test
	void readsComment() throws Exception {
		String json = """
				{"id": 9, "body": "Looks good", "user": {"id": 2, "login": "alice"},
				 "author_association": "MEMBER", "created_at": "2026-11-03T08:00:00Z",
				 "updated_at": "2026-11-03T08:05:00Z", "html_url": "https://github.com/o/r/issues/7#c9"}
				""";

		Comment comment = mapper.readValue(json, Comment.class);

		assertThat(comment.user().login()).isEqualTo("alice");
		assertThat(comment.authorAssociation()).isEqualTo("MEMBER");
		assertThat(comment.createdAt()).isEqualTo(Instant.parse("2026-11-03T08:00:00Z"));
	}

	@Test
	void readsTimelineEventsOfDifferentKinds() throws Exception {
		String labeled = """
				{"id": 11, "event": "labeled", "actor": {"id": 2, "login": "alice"},
				 "created_at": "2026-11-02T09:00:00Z", "label": {"name": "bug", "color": "d73a4a"}}
				""";
		String crossReferenced = """
				{"event": "cross-referenced", "created_at": "2026-11-04T09:00:00Z",
				 "source": {"type": "issue", "issue": {"id": 77, "number": 12, "title": "Other", "state": "open",
				   "repository_url": "https://api.github.com/repos/o/other"}}}
				""";

		TimelineEvent first = mapper.readValue(labeled, TimelineEvent.class);
		TimelineEvent second = mapper.readValue(crossReferenced, TimelineEvent.class);

		assertThat(first.event()).isEqualTo("labeled");
		assertThat(first.label().name()).isEqualTo("bug");
		assertThat(second.id()).isNull();
		assertThat(second.source().issue().number()).isEqualTo(12);
		assertThat(second.source().issue().repositoryUrl()).endsWith("/o/other");
	}

	@Test
	void repoRefBuildsFullName() {
		assertThat(new RepoRef(42, "cvut", "pm-assistant").fullName()).isEqualTo("cvut/pm-assistant");
	}

	@Test
	void issueStateFilterQueryValueIsLowerCase() {
		assertThat(IssueStateFilter.ALL.queryValue()).isEqualTo("all");
		assertThat(IssueStateFilter.OPEN.queryValue()).isEqualTo("open");
	}
}
