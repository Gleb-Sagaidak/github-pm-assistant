package cz.cvut.pmassistant.github;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import cz.cvut.pmassistant.github.http.GitHubHttp;
import cz.cvut.pmassistant.github.model.Comment;
import cz.cvut.pmassistant.github.model.Issue;
import cz.cvut.pmassistant.github.model.IssueLink;
import cz.cvut.pmassistant.github.model.IssueStateFilter;
import cz.cvut.pmassistant.github.model.Label;
import cz.cvut.pmassistant.github.model.RepoRef;
import cz.cvut.pmassistant.github.model.TimelineEvent;
import cz.cvut.pmassistant.github.model.User;

/**
 * {@link GitHubReader} on top of the REST API. One instance serves one installation (one token source in the
 * underlying {@link GitHubHttp}); the repository is passed to every call.
 */
public class RestGitHubReader implements GitHubReader {

	/** GitHub owner and repository names; anything else must never end up in a request path. */
	private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

	private final GitHubHttp http;

	public RestGitHubReader(GitHubHttp http) {
		this.http = http;
	}

	@Override
	public Issue getIssue(RepoRef repo, int number) {
		return http.get(repoPath(repo) + "/issues/" + number, Issue.class);
	}

	@Override
	public List<Issue> listIssues(RepoRef repo, IssueStateFilter state, Instant since) {
		Objects.requireNonNull(state, "state");
		String path = repoPath(repo) + "/issues?state=" + state.queryValue();
		if (since != null) {
			path += "&since=" + since;
		}
		// The issues endpoint also returns pull requests; they are not issues for our purposes.
		return http.getAll(path, Issue.class).stream().filter(issue -> !issue.isPullRequest()).toList();
	}

	@Override
	public List<Comment> listComments(RepoRef repo, int issueNumber) {
		return http.getAll(repoPath(repo) + "/issues/" + issueNumber + "/comments", Comment.class);
	}

	@Override
	public List<TimelineEvent> getTimeline(RepoRef repo, int issueNumber) {
		return http.getAll(repoPath(repo) + "/issues/" + issueNumber + "/timeline", TimelineEvent.class);
	}

	@Override
	public List<Label> listLabels(RepoRef repo) {
		return http.getAll(repoPath(repo) + "/labels", Label.class);
	}

	@Override
	public List<User> listCollaborators(RepoRef repo) {
		return http.getAll(repoPath(repo) + "/collaborators", User.class);
	}

	@Override
	public List<IssueLink> listBlockedBy(RepoRef repo, int issueNumber) {
		return http.getAll(repoPath(repo) + "/issues/" + issueNumber + "/dependencies/blocked_by", IssueLink.class);
	}

	@Override
	public List<IssueLink> listBlocking(RepoRef repo, int issueNumber) {
		return http.getAll(repoPath(repo) + "/issues/" + issueNumber + "/dependencies/blocking", IssueLink.class);
	}

	@Override
	public List<IssueLink> listSubIssues(RepoRef repo, int issueNumber) {
		return http.getAll(repoPath(repo) + "/issues/" + issueNumber + "/sub_issues", IssueLink.class);
	}

	private static String repoPath(RepoRef repo) {
		if (!SAFE_NAME.matcher(repo.owner()).matches() || !SAFE_NAME.matcher(repo.name()).matches()) {
			throw new IllegalArgumentException("Unsafe repository name: " + repo.owner() + "/" + repo.name());
		}
		return "/repos/" + repo.owner() + "/" + repo.name();
	}
}
