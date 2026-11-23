package cz.cvut.pmassistant.github.http;

/**
 * Pagination cannot be completed safely: too many pages, a loop in the {@code next} links, or a link that
 * leads away from the configured GitHub host. Deliberately not a {@link GitHubApiException}: it is not
 * retried, and a truncated result is never returned as if it were complete.
 */
public class PaginationException extends RuntimeException {

	public PaginationException(String message) {
		super(message);
	}

	public PaginationException(String message, Throwable cause) {
		super(message, cause);
	}
}
