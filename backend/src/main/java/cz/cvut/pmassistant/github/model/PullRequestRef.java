package cz.cvut.pmassistant.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The {@code pull_request} object that GitHub adds to items of the issues list that are pull requests. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PullRequestRef(String url) {
}
