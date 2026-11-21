package cz.cvut.pmassistant.github;

import java.time.Instant;
import java.util.List;

import cz.cvut.pmassistant.github.model.Comment;
import cz.cvut.pmassistant.github.model.Issue;
import cz.cvut.pmassistant.github.model.IssueLink;
import cz.cvut.pmassistant.github.model.IssueStateFilter;
import cz.cvut.pmassistant.github.model.Label;
import cz.cvut.pmassistant.github.model.RepoRef;
import cz.cvut.pmassistant.github.model.TimelineEvent;
import cz.cvut.pmassistant.github.model.User;

/**
 * Read-only access to repository-level GitHub data (REST). An instance is bound to one installation
 * (one token); the repository is always an argument, there is no "current repository".
 * <p>
 * It is a different interface (and different beans) from {@link GitHubWriter}: agent tools receive only readers.
 */
public interface GitHubReader {

	Issue getIssue(RepoRef repo, int number);

	/**
	 * Lists issues, pull requests excluded.
	 *
	 * @param since only issues updated at or after this moment; {@code null} for no limit
	 */
	List<Issue> listIssues(RepoRef repo, IssueStateFilter state, Instant since);

	List<Comment> listComments(RepoRef repo, int issueNumber);

	/** Timeline events: labeled, assigned, cross-referenced, commented, ... */
	List<TimelineEvent> getTimeline(RepoRef repo, int issueNumber);

	List<Label> listLabels(RepoRef repo);

	List<User> listCollaborators(RepoRef repo);

	/** Issues that block the given one. */
	List<IssueLink> listBlockedBy(RepoRef repo, int issueNumber);

	/** Issues that the given one blocks. */
	List<IssueLink> listBlocking(RepoRef repo, int issueNumber);

	List<IssueLink> listSubIssues(RepoRef repo, int issueNumber);
}
