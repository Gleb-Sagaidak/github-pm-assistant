package cz.cvut.pmassistant.github.http;

import org.springframework.http.HttpHeaders;

/** A successful response: the parsed body plus the headers (pagination needs {@code Link}). */
public record GitHubResponse<T>(T body, HttpHeaders headers) {
}
