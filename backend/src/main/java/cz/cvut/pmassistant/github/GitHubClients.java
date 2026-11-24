package cz.cvut.pmassistant.github;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.client.ClientHttpRequestFactory;

import cz.cvut.pmassistant.github.auth.AppJwtFactory;
import cz.cvut.pmassistant.github.auth.InstallationTokenProvider;
import cz.cvut.pmassistant.github.auth.TokenScope;
import cz.cvut.pmassistant.github.http.BearerTokenSource;
import cz.cvut.pmassistant.github.http.GitHubGraphQl;
import cz.cvut.pmassistant.github.http.GitHubHttp;
import cz.cvut.pmassistant.github.http.RetryExecutor;

/**
 * Creates the GitHub clients and wires them to the right credentials. Nothing here is tied to a repository or
 * workspace: readers are made <em>per installation</em> (one installation is one account, one token), and the
 * repository is an argument of every call.
 * <ul>
 * <li>{@link #appClient()} acts as the App itself (JWT).</li>
 * <li>{@link #readerFor(long)} and {@link #projectsReaderFor(long)} act as an installation, with a token
 * narrowed to read-only permissions, so even a bug in an agent tool cannot write to GitHub with it.</li>
 * <li>{@link #userClientFor(BearerTokenSource)} acts as a signed-in user, for onboarding only.</li>
 * </ul>
 * Readers are created once per installation and reused; they all share one HTTP transport.
 */
public class GitHubClients {

	/** What reading issues needs: issues and the always-granted repository metadata (collaborators, labels). */
	static final TokenScope ISSUES_READ_SCOPE = TokenScope.ofPermissions(Map.of("issues", "read", "metadata", "read"));

	/** What reading Projects v2 boards of an organization needs. */
	static final TokenScope PROJECTS_READ_SCOPE = TokenScope
			.ofPermissions(Map.of("organization_projects", "read", "metadata", "read"));

	private final String baseUrl;
	private final String apiVersion;
	private final Clock clock;
	private final RetryExecutor retry;
	private final ClientHttpRequestFactory transport = GitHubHttp.newRequestFactory();
	private final InstallationTokenProvider tokens;
	private final GitHubAppClient appClient;
	private final Map<Long, GitHubReader> readers = new ConcurrentHashMap<>();
	private final Map<Long, GitHubProjectsReader> projectsReaders = new ConcurrentHashMap<>();

	/**
	 * @param baseUrl {@code https://api.github.com}
	 */
	public GitHubClients(String baseUrl, String apiVersion, AppJwtFactory appJwt, Clock clock,
			RetryExecutor retry) {
		this.baseUrl = baseUrl;
		this.apiVersion = apiVersion;
		this.clock = clock;
		this.retry = retry;
		GitHubHttp appHttp = http(appJwt::create);
		this.tokens = new InstallationTokenProvider(appHttp, clock);
		this.appClient = new GitHubAppClient(appHttp);
	}

	/** Calls made as the App itself: installation sync and lookup. */
	public GitHubAppClient appClient() {
		return appClient;
	}

	/** Read-only access to the repositories of one installation. */
	public GitHubReader readerFor(long installationId) {
		return readers.computeIfAbsent(installationId,
				id -> new RestGitHubReader(http(tokens.tokenSource(id, ISSUES_READ_SCOPE))));
	}

	/**
	 * Read-only access to the Projects v2 boards of one installation. Only installations on organizations can
	 * grant the permission this needs; for a personal account the token request is refused by GitHub.
	 */
	public GitHubProjectsReader projectsReaderFor(long installationId) {
		return projectsReaders.computeIfAbsent(installationId, id -> new ProjectsGraphQlReader(
				new GitHubGraphQl(http(tokens.tokenSource(id, PROJECTS_READ_SCOPE)))));
	}

	/**
	 * A client that acts with one user's token. Not cached: it belongs to that user's session, and the token
	 * source must read the token from there.
	 */
	public GitHubUserClient userClientFor(BearerTokenSource userToken) {
		return new GitHubUserClient(http(userToken));
	}

	/** To forget the tokens of an installation that was removed or answered 401. */
	public InstallationTokenProvider tokens() {
		return tokens;
	}

	private GitHubHttp http(BearerTokenSource tokenSource) {
		return new GitHubHttp(baseUrl, apiVersion, tokenSource, clock, retry, GitHubHttp.DEFAULT_MAX_PAGES,
				transport);
	}
}
