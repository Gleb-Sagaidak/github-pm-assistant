package cz.cvut.pmassistant.github.http;

/**
 * Supplies the bearer token for each request: the App JWT for app-level calls, an installation token for
 * installation-level calls. It is asked on every request, so token refreshing stays in the supplier
 * and {@link GitHubHttp} never stores a token.
 */
@FunctionalInterface
public interface BearerTokenSource {

	String token();
}
