package cz.cvut.pmassistant.github.http;

import java.time.Duration;

/**
 * Base of all errors raised by the GitHub client. Carries what the audit log needs (FR10):
 * HTTP method, path, status and GitHub's request id. {@code status} is 0 when no response was received.
 * <p>
 * The base class itself is used for non-retryable client errors without a dedicated subtype (400, 422, ...).
 */
public sealed class GitHubApiException extends RuntimeException
		permits GitHubApiException.RateLimited, GitHubApiException.NotFound, GitHubApiException.Unauthorized,
		GitHubApiException.Forbidden, GitHubApiException.Transient {

	private final String method;
	private final String path;
	private final int status;
	private final String requestId;

	public GitHubApiException(String message, String method, String path, int status, String requestId,
			Throwable cause) {
		super(format(message, method, path, status, requestId), cause);
		this.method = method;
		this.path = path;
		this.status = status;
		this.requestId = requestId;
	}

	private static String format(String message, String method, String path, int status, String requestId) {
		StringBuilder sb = new StringBuilder(method).append(' ').append(path).append(" -> ");
		sb.append(status == 0 ? "no response" : String.valueOf(status));
		if (requestId != null) {
			sb.append(" [request-id ").append(requestId).append(']');
		}
		if (message != null && !message.isBlank()) {
			sb.append(": ").append(message);
		}
		return sb.toString();
	}

	public String method() {
		return method;
	}

	public String path() {
		return path;
	}

	public int status() {
		return status;
	}

	/** Value of the {@code x-github-request-id} header; null if there was no response. */
	public String requestId() {
		return requestId;
	}

	/** 429, or 403 with rate-limit signs. Retryable; {@code retryAfter} is the server's hint and may be null. */
	public static final class RateLimited extends GitHubApiException {

		private final Duration retryAfter;

		public RateLimited(String message, String method, String path, int status, String requestId,
				Duration retryAfter) {
			super(message, method, path, status, requestId, null);
			this.retryAfter = retryAfter;
		}

		public Duration retryAfter() {
			return retryAfter;
		}
	}

	/** 404. Not retried. GitHub also answers 404 for resources the token is not allowed to see. */
	public static final class NotFound extends GitHubApiException {

		public NotFound(String message, String method, String path, String requestId) {
			super(message, method, path, 404, requestId, null);
		}
	}

	/** 401: bad or expired credentials. Not retried. */
	public static final class Unauthorized extends GitHubApiException {

		public Unauthorized(String message, String method, String path, String requestId) {
			super(message, method, path, 401, requestId, null);
		}
	}

	/** 403 that is not a rate limit: the token lacks the permission. Not retried. */
	public static final class Forbidden extends GitHubApiException {

		public Forbidden(String message, String method, String path, String requestId) {
			super(message, method, path, 403, requestId, null);
		}
	}

	/** 5xx or a network failure. Retryable; surfaces only after the retries are exhausted. */
	public static final class Transient extends GitHubApiException {

		public Transient(String message, String method, String path, int status, String requestId,
				Throwable cause) {
			super(message, method, path, status, requestId, cause);
		}
	}
}
