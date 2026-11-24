package cz.cvut.pmassistant.github.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A user's permission on a repository.
 * <p>
 * GitHub reports two values. {@code permission} is the legacy coarse one ({@code admin}, {@code write},
 * {@code read}, {@code none}): {@code maintain} is reported as {@code write} and {@code triage} as {@code read}.
 * {@code roleName} is the precise role (or the name of a custom role). Decisions must use
 * {@link #hasAtLeast(RepoRole)}, which prefers {@code roleName}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CollaboratorPermission(
		String permission,
		@JsonProperty("role_name") String roleName,
		User user) {

	/**
	 * Whether the user has this role or a stronger one. A custom role name is judged by the coarse
	 * {@code permission} it is based on, which can only under-report (a role built on triage reads as
	 * {@code read}), never grant more than the user has.
	 */
	public boolean hasAtLeast(RepoRole required) {
		RepoRole actual = RepoRole.fromName(roleName);
		if (actual == null) {
			actual = RepoRole.fromName(permission);
		}
		return actual != null && actual.compareTo(required) >= 0;
	}

	@JsonIgnore
	public boolean hasNoAccess() {
		return !hasAtLeast(RepoRole.READ);
	}
}
