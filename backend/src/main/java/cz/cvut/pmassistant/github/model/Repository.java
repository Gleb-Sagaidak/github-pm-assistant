package cz.cvut.pmassistant.github.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A repository as listed for the signed-in user. {@code permissions} describe what <em>that user</em> may do
 * there (only present in answers to user-token requests).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Repository(
		long id,
		String name,
		@JsonProperty("full_name") String fullName,
		User owner,
		@JsonProperty("private") boolean isPrivate,
		Permissions permissions) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Permissions(Boolean admin, Boolean maintain, Boolean push, Boolean triage, Boolean pull) {
	}

	/** The user may maintain the repository (the minimum for putting it into a workspace). */
	@JsonIgnore
	public boolean isMaintainedByUser() {
		return permissions != null
				&& (Boolean.TRUE.equals(permissions.maintain()) || Boolean.TRUE.equals(permissions.admin()));
	}
}
