package cz.cvut.pmassistant.github.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of an issue timeline. Only the fields the agents need are modelled; which of them are set depends
 * on {@code event}: {@code label} for labeled/unlabeled, {@code assignee} for assigned/unassigned,
 * {@code source} for cross-referenced, {@code body} for commented. The shape is confirmed against
 * real fixtures when the timeline reader is implemented.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TimelineEvent(
		Long id,
		String event,
		User actor,
		@JsonProperty("created_at") Instant createdAt,
		EventLabel label,
		User assignee,
		Source source,
		String body) {

	/** The label in a labeled/unlabeled event: GitHub gives only name and color here, no numeric id. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EventLabel(String name, String color) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Source(String type, IssueLink issue) {
	}
}
