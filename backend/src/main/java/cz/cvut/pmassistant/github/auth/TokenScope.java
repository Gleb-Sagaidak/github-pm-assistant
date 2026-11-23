package cz.cvut.pmassistant.github.auth;

import java.util.Map;
import java.util.Set;

/**
 * Optionally narrows an installation token below what the installation was granted (least privilege:
 * a token used for reading can be limited to {@code issues: read}, one for writing to {@code issues: write}).
 * GitHub rejects permissions the installation does not have.
 *
 * @param permissions   permission name to {@code read}/{@code write}, e.g. {@code issues -> read}; empty = all
 * @param repositoryIds numeric ids of the repositories the token may access; empty = all of the installation's
 */
public record TokenScope(Map<String, String> permissions, Set<Long> repositoryIds) {

	/** Everything the installation was granted. */
	public static final TokenScope FULL = new TokenScope(Map.of(), Set.of());

	public TokenScope {
		permissions = Map.copyOf(permissions);
		repositoryIds = Set.copyOf(repositoryIds);
	}

	public static TokenScope ofPermissions(Map<String, String> permissions) {
		return new TokenScope(permissions, Set.of());
	}

	boolean isFull() {
		return permissions.isEmpty() && repositoryIds.isEmpty();
	}
}
