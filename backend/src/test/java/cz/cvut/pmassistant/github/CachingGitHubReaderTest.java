package cz.cvut.pmassistant.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import cz.cvut.pmassistant.github.http.GitHubApiException;
import cz.cvut.pmassistant.github.model.CollaboratorPermission;
import cz.cvut.pmassistant.github.model.Issue;
import cz.cvut.pmassistant.github.model.IssueStateFilter;
import cz.cvut.pmassistant.github.model.Label;
import cz.cvut.pmassistant.github.model.RepoRef;
import cz.cvut.pmassistant.github.model.User;

/** The run-scoped cache: fresh at the start of every run, no repeated GitHub calls inside one. */
class CachingGitHubReaderTest {

	private static final RepoRef REPO = new RepoRef(1, "cvut-team", "pm-assistant");
	private static final RepoRef OTHER_REPO = new RepoRef(2, "cvut-team", "infrastructure");

	private final GitHubReader github = mock(GitHubReader.class);
	private final RunScopedCache cache = new RunScopedCache();
	private final GitHubReader reader = new CachingGitHubReader(github, cache);

	private static Label label(long id, String name) {
		return new Label(id, name, "ffffff", null);
	}

	@Test
	void repeatedQuestionsAreAnsweredFromTheCache() {
		when(github.listLabels(REPO)).thenReturn(List.of(label(1, "bug")));

		assertThat(reader.listLabels(REPO)).extracting(Label::name).containsExactly("bug");
		assertThat(reader.listLabels(REPO)).extracting(Label::name).containsExactly("bug");

		verify(github, times(1)).listLabels(REPO);
	}

	@Test
	void differentRepositoriesAreCachedApart() {
		when(github.listLabels(REPO)).thenReturn(List.of(label(1, "bug")));
		when(github.listLabels(OTHER_REPO)).thenReturn(List.of(label(9, "infra")));

		assertThat(reader.listLabels(REPO)).extracting(Label::name).containsExactly("bug");
		assertThat(reader.listLabels(OTHER_REPO)).extracting(Label::name).containsExactly("infra");

		verify(github).listLabels(REPO);
		verify(github).listLabels(OTHER_REPO);
	}

	@Test
	void differentArgumentsAreCachedApart() {
		reader.listComments(REPO, 1);
		reader.listComments(REPO, 2);
		reader.listComments(REPO, 1);

		verify(github, times(1)).listComments(REPO, 1);
		verify(github, times(1)).listComments(REPO, 2);
	}

	@Test
	void issueListsAreKeyedByStateAndSince() {
		Instant since = Instant.parse("2026-11-01T00:00:00Z");

		reader.listIssues(REPO, IssueStateFilter.OPEN, null);
		reader.listIssues(REPO, IssueStateFilter.OPEN, null);
		reader.listIssues(REPO, IssueStateFilter.CLOSED, null);
		reader.listIssues(REPO, IssueStateFilter.OPEN, since);
		reader.listIssues(REPO, IssueStateFilter.OPEN, since);

		verify(github, times(1)).listIssues(REPO, IssueStateFilter.OPEN, null);
		verify(github, times(1)).listIssues(REPO, IssueStateFilter.CLOSED, null);
		verify(github, times(1)).listIssues(REPO, IssueStateFilter.OPEN, since);
	}

	@Test
	void eachRunStartsWithFreshData() {
		when(github.listLabels(REPO)).thenReturn(List.of(label(1, "old")))
				.thenReturn(List.of(label(1, "renamed")));

		GitHubReader firstRun = new CachingGitHubReader(github, new RunScopedCache());
		GitHubReader secondRun = new CachingGitHubReader(github, new RunScopedCache());

		assertThat(firstRun.listLabels(REPO)).extracting(Label::name).containsExactly("old");
		assertThat(secondRun.listLabels(REPO)).extracting(Label::name).containsExactly("renamed");
	}

	@Test
	void aFailedCallIsNotRememberedAndIsTriedAgain() {
		when(github.listCollaborators(REPO))
				.thenThrow(new GitHubApiException.Transient("boom", "GET", "/x", 502, null, null))
				.thenReturn(List.of(new User(1, "alice", null)));

		assertThatThrownBy(() -> reader.listCollaborators(REPO)).isInstanceOf(GitHubApiException.Transient.class);
		assertThat(reader.listCollaborators(REPO)).extracting(User::login).containsExactly("alice");
		assertThat(cache.size()).isEqualTo(1);
	}

	@Test
	void sharedListsCannotBeChangedByACaller() {
		List<Label> fromGitHub = new ArrayList<>(List.of(label(1, "bug")));
		when(github.listLabels(REPO)).thenReturn(fromGitHub);

		List<Label> first = reader.listLabels(REPO);

		assertThatThrownBy(() -> first.add(label(2, "extra"))).isInstanceOf(UnsupportedOperationException.class);
		fromGitHub.add(label(3, "added later at GitHub"));
		assertThat(reader.listLabels(REPO)).hasSize(1);
	}

	@Test
	void singleIssuesAreCachedByNumber() {
		Issue issue = mock(Issue.class);
		when(github.getIssue(REPO, 7)).thenReturn(issue);

		assertThat(reader.getIssue(REPO, 7)).isSameAs(issue);
		assertThat(reader.getIssue(REPO, 7)).isSameAs(issue);
		reader.getIssue(REPO, 8);

		verify(github, times(1)).getIssue(REPO, 7);
		verify(github, times(1)).getIssue(REPO, 8);
	}

	@Test
	void permissionsAreAlwaysAskedFromGitHub() {
		CollaboratorPermission triage = new CollaboratorPermission("read", "triage", null);
		CollaboratorPermission removed = new CollaboratorPermission("none", "none", null);
		when(github.getCollaboratorPermission(REPO, "bob")).thenReturn(triage).thenReturn(removed);

		assertThat(reader.getCollaboratorPermission(REPO, "bob")).isSameAs(triage);
		assertThat(reader.getCollaboratorPermission(REPO, "bob")).isSameAs(removed);

		verify(github, times(2)).getCollaboratorPermission(REPO, "bob");
		assertThat(cache.size()).isZero();
	}

	@Test
	void everyReadIsCachedExceptPermissions() {
		reader.getTimeline(REPO, 1);
		reader.getTimeline(REPO, 1);
		reader.listBlockedBy(REPO, 1);
		reader.listBlockedBy(REPO, 1);
		reader.listBlocking(REPO, 1);
		reader.listBlocking(REPO, 1);
		reader.listSubIssues(REPO, 1);
		reader.listSubIssues(REPO, 1);

		verify(github, times(1)).getTimeline(REPO, 1);
		verify(github, times(1)).listBlockedBy(REPO, 1);
		verify(github, times(1)).listBlocking(REPO, 1);
		verify(github, times(1)).listSubIssues(REPO, 1);
		verify(github, never()).getIssue(any(), anyInt());
	}
}
