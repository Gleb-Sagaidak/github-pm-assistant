package cz.cvut.pmassistant.github.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Milestone(
		int number,
		String title,
		String state,
		@JsonProperty("due_on") Instant dueOn) {
}
