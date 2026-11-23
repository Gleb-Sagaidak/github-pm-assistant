package cz.cvut.pmassistant.github;

import java.util.List;
import java.util.Optional;

import cz.cvut.pmassistant.github.http.GitHubApiException;
import cz.cvut.pmassistant.github.http.GitHubHttp;
import cz.cvut.pmassistant.github.model.Installation;

/**
 * Calls that the GitHub App makes as itself (authenticated with the App JWT, not an installation token):
 * which accounts installed it, and where it is installed.
 */
public class GitHubAppClient {

	private final GitHubHttp http;

	/**
	 * @param http a client whose token source is the App JWT
	 */
	public GitHubAppClient(GitHubHttp http) {
		this.http = http;
	}

	/** All installations of the App; used to detect removed and suspended ones. */
	public List<Installation> listInstallations() {
		return http.getAll("/app/installations", Installation.class);
	}

	/**
	 * The installation that covers a repository, or empty if the App is not installed there
	 * (GitHub answers 404 for both "no such repository" and "not installed").
	 */
	public Optional<Installation> findInstallationForRepo(String owner, String name) {
		try {
			return Optional.ofNullable(http.get("/repos/" + owner + "/" + name + "/installation", Installation.class));
		}
		catch (GitHubApiException.NotFound e) {
			return Optional.empty();
		}
	}
}
