package cz.cvut.pmassistant.github.model;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An installation of the GitHub App on an account. {@code permissions} are what the account owner granted
 * (a token can be narrowed below them, never widened). {@code repositorySelection} is {@code all} or
 * {@code selected}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Installation(
		long id,
		Account account,
		@JsonProperty("repository_selection") String repositorySelection,
		Map<String, String> permissions,
		@JsonProperty("suspended_at") Instant suspendedAt,
		@JsonProperty("created_at") Instant createdAt,
		@JsonProperty("updated_at") Instant updatedAt) {

	public Installation {
		permissions = permissions == null ? Map.of() : Map.copyOf(permissions);
	}

	/** The GitHub account the App is installed on; {@code type} is {@code Organization} or {@code User}. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Account(long id, String login, String type) {
	}

	@JsonIgnore
	public boolean isSuspended() {
		return suspendedAt != null;
	}

	@JsonIgnore
	public boolean isOrganization() {
		return account != null && "Organization".equalsIgnoreCase(account.type());
	}
}
