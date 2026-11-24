package cz.cvut.pmassistant.tenant;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import cz.cvut.pmassistant.github.model.ProjectRef;
import cz.cvut.pmassistant.github.model.RepoRef;

/**
 * What an agent run may touch: one installation, the repositories of the workspace and, optionally, its board.
 * Tools accept only repositories of this set, so an agent cannot be talked into reading another workspace.
 *
 * @param project the workspace's Projects v2 board, or null if it has none
 */
public record WorkspaceRef(UUID workspaceId, long installationId, Set<RepoRef> repos, ProjectRef project) {

	public WorkspaceRef {
		Objects.requireNonNull(workspaceId, "workspaceId");
		repos = Set.copyOf(repos);
	}

	/** Whether the repository belongs to the workspace. Compared by the numeric id: names can change. */
	public boolean containsRepo(RepoRef repo) {
		return repos.stream().anyMatch(known -> known.githubRepoId() == repo.githubRepoId());
	}
}
