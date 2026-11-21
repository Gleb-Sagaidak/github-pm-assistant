package cz.cvut.pmassistant.github.model;

import java.time.Instant;

/**
 * A card on a Projects v2 board. Items may come from repositories outside the workspace, hence
 * {@code repositoryFullName}. {@code number} is null for draft issues. {@code status} is the value of the
 * single-select "Status" field and {@code iterationId} the id of the assigned {@link Iteration}; both may be null.
 * Built by the GraphQL reader, not deserialized directly.
 */
public record ProjectItem(
		String id,
		Integer number,
		String title,
		IssueState state,
		Instant updatedAt,
		String repositoryFullName,
		String status,
		String iterationId) {
}
