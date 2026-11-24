package cz.cvut.pmassistant.github;

import java.time.Instant;
import java.util.List;

import cz.cvut.pmassistant.github.model.CollaboratorPermission;
import cz.cvut.pmassistant.github.model.Comment;
import cz.cvut.pmassistant.github.model.Issue;
import cz.cvut.pmassistant.github.model.IssueLink;
import cz.cvut.pmassistant.github.model.IssueStateFilter;
import cz.cvut.pmassistant.github.model.Label;
import cz.cvut.pmassistant.github.model.RepoRef;
import cz.cvut.pmassistant.github.model.TimelineEvent;
import cz.cvut.pmassistant.github.model.User;

/**
 * Wraps a {@link GitHubReader} for one agent run: the first call for a given question goes to GitHub, repeats
 * are answered from the run's {@link RunScopedCache}. Lists are copied into unmodifiable lists, because one
 * cached list is handed to every caller.
 * <p>
 * Permissions are not cached: they guard approvals and must reflect GitHub at that moment.
 */
public class CachingGitHubReader implements GitHubReader {

	private final GitHubReader delegate;
	private final RunScopedCache cache;

	public CachingGitHubReader(GitHubReader delegate, RunScopedCache cache) {
		this.delegate = delegate;
		this.cache = cache;
	}

	@Override
	public Issue getIssue(RepoRef repo, int number) {
		return cache.get(List.of("getIssue", repo.githubRepoId(), number), () -> delegate.getIssue(repo, number));
	}

	@Override
	public List<Issue> listIssues(RepoRef repo, IssueStateFilter state, Instant since) {
		// List.of rejects null, and 'since' may be null: spell it out.
		Object key = List.of("listIssues", repo.githubRepoId(), state, String.valueOf(since));
		return cache.get(key, () -> List.copyOf(delegate.listIssues(repo, state, since)));
	}

	@Override
	public List<Comment> listComments(RepoRef repo, int issueNumber) {
		return cache.get(List.of("listComments", repo.githubRepoId(), issueNumber),
				() -> List.copyOf(delegate.listComments(repo, issueNumber)));
	}

	@Override
	public List<TimelineEvent> getTimeline(RepoRef repo, int issueNumber) {
		return cache.get(List.of("getTimeline", repo.githubRepoId(), issueNumber),
				() -> List.copyOf(delegate.getTimeline(repo, issueNumber)));
	}

	@Override
	public List<Label> listLabels(RepoRef repo) {
		return cache.get(List.of("listLabels", repo.githubRepoId()), () -> List.copyOf(delegate.listLabels(repo)));
	}

	@Override
	public List<User> listCollaborators(RepoRef repo) {
		return cache.get(List.of("listCollaborators", repo.githubRepoId()),
				() -> List.copyOf(delegate.listCollaborators(repo)));
	}

	@Override
	public CollaboratorPermission getCollaboratorPermission(RepoRef repo, String login) {
		return delegate.getCollaboratorPermission(repo, login);
	}

	@Override
	public List<IssueLink> listBlockedBy(RepoRef repo, int issueNumber) {
		return cache.get(List.of("listBlockedBy", repo.githubRepoId(), issueNumber),
				() -> List.copyOf(delegate.listBlockedBy(repo, issueNumber)));
	}

	@Override
	public List<IssueLink> listBlocking(RepoRef repo, int issueNumber) {
		return cache.get(List.of("listBlocking", repo.githubRepoId(), issueNumber),
				() -> List.copyOf(delegate.listBlocking(repo, issueNumber)));
	}

	@Override
	public List<IssueLink> listSubIssues(RepoRef repo, int issueNumber) {
		return cache.get(List.of("listSubIssues", repo.githubRepoId(), issueNumber),
				() -> List.copyOf(delegate.listSubIssues(repo, issueNumber)));
	}
}
