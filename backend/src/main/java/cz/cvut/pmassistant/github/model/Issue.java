package cz.cvut.pmassistant.github.model;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An issue (or a pull request, when it comes from the issues list: see {@link #isPullRequest()}).
 * <p>
 * API version 2026-03-10 removed the singular {@code assignee} field, so only {@code assignees} is modelled.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Issue(
		long id,
		int number,
		String title,
		String body,
		IssueState state,
		@JsonProperty("state_reason") String stateReason,
		User user,
		List<Label> labels,
		List<User> assignees,
		Milestone milestone,
		int comments,
		@JsonProperty("created_at") Instant createdAt,
		@JsonProperty("updated_at") Instant updatedAt,
		@JsonProperty("closed_at") Instant closedAt,
		@JsonProperty("html_url") String htmlUrl,
		@JsonProperty("pull_request") PullRequestRef pullRequest) {

	public Issue {
		labels = labels == null ? List.of() : List.copyOf(labels);
		assignees = assignees == null ? List.of() : List.copyOf(assignees);
	}

	@JsonIgnore
	public boolean isPullRequest() {
		return pullRequest != null;
	}
}
