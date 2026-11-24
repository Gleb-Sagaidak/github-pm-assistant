package cz.cvut.pmassistant.github.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;

import tools.jackson.databind.JsonNode;

/**
 * Runs GraphQL queries through {@link GitHubHttp} (same headers, token and retries as REST).
 * <p>
 * GraphQL reports failures with HTTP 200 and an {@code errors} array, so every response is checked and
 * turned into the same {@link GitHubApiException} hierarchy as REST failures: {@code NOT_FOUND} becomes
 * {@link GitHubApiException.NotFound}, {@code FORBIDDEN} {@link GitHubApiException.Forbidden},
 * {@code RATE_LIMITED} {@link GitHubApiException.RateLimited}. A response with errors is never used, even if
 * it also carries partial data. Rate-limit errors of this kind are not retried automatically, because the
 * HTTP layer only sees a successful status.
 */
public class GitHubGraphQl {

	private static final String PATH = "/graphql";
	private static final ParameterizedTypeReference<JsonNode> JSON = ParameterizedTypeReference.forType(JsonNode.class);

	private final GitHubHttp http;

	public GitHubGraphQl(GitHubHttp http) {
		this.http = http;
	}

	/**
	 * @param variables query variables; values may be null (for example a first-page cursor)
	 * @return the {@code data} object of the response
	 * @throws GitHubApiException if GitHub reported errors or returned no data
	 */
	public JsonNode query(String query, Map<String, Object> variables) {
		Map<String, Object> body = Map.of("query", query, "variables", variables);
		GitHubResponse<JsonNode> response = http.exchange(HttpMethod.POST, PATH, body, JSON);
		String requestId = response.headers().getFirst("x-github-request-id");
		JsonNode root = response.body();
		if (root == null) {
			throw new GitHubApiException("empty GraphQL response", "POST", PATH, 200, requestId, null);
		}
		JsonNode errors = root.get("errors");
		if (errors != null && errors.isArray() && errors.size() > 0) {
			throw toException(errors, requestId);
		}
		JsonNode data = root.get("data");
		if (data == null || data.isNull()) {
			throw new GitHubApiException("GraphQL response has no data", "POST", PATH, 200, requestId, null);
		}
		return data;
	}

	private static GitHubApiException toException(JsonNode errors, String requestId) {
		List<String> messages = new ArrayList<>();
		String type = null;
		for (JsonNode error : errors) {
			if (type == null && error.hasNonNull("type")) {
				type = error.get("type").asString();
			}
			if (error.hasNonNull("message")) {
				messages.add(error.get("message").asString());
			}
		}
		String message = messages.isEmpty() ? "GraphQL error" : String.join("; ", messages);
		if (message.length() > 300) {
			message = message.substring(0, 300) + "...";
		}
		if (type == null) {
			return new GitHubApiException(message, "POST", PATH, 200, requestId, null);
		}
		return switch (type) {
			case "NOT_FOUND" -> new GitHubApiException.NotFound(message, "POST", PATH, requestId);
			case "FORBIDDEN", "INSUFFICIENT_SCOPES" ->
				new GitHubApiException.Forbidden(message, "POST", PATH, requestId);
			case "RATE_LIMITED" -> new GitHubApiException.RateLimited(message, "POST", PATH, 200, requestId, null);
			default -> new GitHubApiException(message, "POST", PATH, 200, requestId, null);
		};
	}
}
