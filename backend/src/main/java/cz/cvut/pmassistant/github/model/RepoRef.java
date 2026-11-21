package cz.cvut.pmassistant.github.model;

import java.util.Objects;

/**
 * Identifies a repository. The numeric id is the stable identity (it survives renames);
 * {@code owner} and {@code name} are a display/URL cache and may be refreshed on every read.
 */
public record RepoRef(long githubRepoId, String owner, String name) {

	public RepoRef {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(name, "name");
	}

	/** {@code owner/name}, as used in REST paths. */
	public String fullName() {
		return owner + "/" + name;
	}
}
