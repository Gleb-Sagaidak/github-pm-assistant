package cz.cvut.pmassistant.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A reference to another issue: a blocker, a blocked issue, a sub-issue, or the source of a cross-reference.
 * It may live in another repository, so the repository URL is kept. {@code id} is the internal issue id
 * (the dependency write endpoint needs it, not the number).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IssueLink(
		long id,
		int number,
		String title,
		IssueState state,
		@JsonProperty("repository_url") String repositoryUrl,
		@JsonProperty("html_url") String htmlUrl) {
}
