package cz.cvut.pmassistant.github.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum IssueState {
	@JsonProperty("open") OPEN,
	@JsonProperty("closed") CLOSED
}
