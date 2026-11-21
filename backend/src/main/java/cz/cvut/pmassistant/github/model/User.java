package cz.cvut.pmassistant.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record User(
		long id,
		String login,
		@JsonProperty("avatar_url") String avatarUrl) {
}
