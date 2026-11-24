package cz.cvut.pmassistant.archfixture;

import java.util.logging.Logger;

import cz.cvut.pmassistant.github.GitHubWriter;

/**
 * Deliberately breaks the architecture rules, so that {@code ArchitectureTest} can show they catch violations
 * (a rule that matches nothing would pass forever). It is never used and is left out of the real analysis.
 */
public class WritesToGitHub {

	private final GitHubWriter writer;
	private final Logger sdkLikeDependency = Logger.getLogger("fixture");

	public WritesToGitHub(GitHubWriter writer) {
		this.writer = writer;
	}

	GitHubWriter writer() {
		return writer;
	}

	Logger logger() {
		return sdkLikeDependency;
	}
}
