package cz.cvut.pmassistant.github.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Comment(
		long id,
		String body,
		User user,
		@JsonProperty("author_association") String authorAssociation,
		@JsonProperty("created_at") Instant createdAt,
		@JsonProperty("updated_at") Instant updatedAt,
		@JsonProperty("html_url") String htmlUrl) {
}
