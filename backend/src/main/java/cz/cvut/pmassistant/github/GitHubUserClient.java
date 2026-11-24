package cz.cvut.pmassistant.github;

import java.util.List;

import org.springframework.core.ParameterizedTypeReference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import cz.cvut.pmassistant.github.http.GitHubHttp;
import cz.cvut.pmassistant.github.model.Installation;
import cz.cvut.pmassistant.github.model.Repository;

/**
 * Calls made with a <em>user's</em> token (the GitHub App's user-to-server token from the sign-in), so the
 * answers show what that person can see. Used only while onboarding; agents and the executor never use a user
 * token. The token lives in the user's session and is never stored: the {@link GitHubHttp} given here takes it
 * from a source tied to that session.
 */
public class GitHubUserClient {

	private final GitHubHttp http;

	/**
	 * @param http a client whose token source is the user's access token
	 */
	public GitHubUserClient(GitHubHttp http) {
		this.http = http;
	}

	/** Installations of the App that the user can access. */
	public List<Installation> listInstallations() {
		return http.getAll("/user/installations", new ParameterizedTypeReference<InstallationsPage>() {
		}, InstallationsPage::installations);
	}

	/** Repositories of an installation that the user can access, with the user's own permissions. */
	public List<Repository> listInstallationRepositories(long installationId) {
		return http.getAll("/user/installations/" + installationId + "/repositories",
				new ParameterizedTypeReference<RepositoriesPage>() {
				}, RepositoriesPage::repositories);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record InstallationsPage(List<Installation> installations) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RepositoriesPage(List<Repository> repositories) {
	}
}
