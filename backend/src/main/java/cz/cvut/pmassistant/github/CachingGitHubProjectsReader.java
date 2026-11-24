package cz.cvut.pmassistant.github;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import cz.cvut.pmassistant.github.model.Iteration;
import cz.cvut.pmassistant.github.model.ProjectItem;
import cz.cvut.pmassistant.github.model.ProjectRef;
import cz.cvut.pmassistant.github.model.ProjectSummary;

/**
 * Per-run caching for board reads, like {@link CachingGitHubReader}. The current iteration is worked out from
 * the cached list of iterations, so asking for it costs nothing after the iterations were read once.
 */
public class CachingGitHubProjectsReader implements GitHubProjectsReader {

	private final GitHubProjectsReader delegate;
	private final RunScopedCache cache;

	public CachingGitHubProjectsReader(GitHubProjectsReader delegate, RunScopedCache cache) {
		this.delegate = delegate;
		this.cache = cache;
	}

	/** Not cached: only the onboarding screen asks for it, outside any agent run. */
	@Override
	public List<ProjectSummary> listProjects(String ownerLogin) {
		return delegate.listProjects(ownerLogin);
	}

	@Override
	public List<ProjectItem> listItems(ProjectRef project) {
		return cache.get(List.of("listItems", project), () -> List.copyOf(delegate.listItems(project)));
	}

	@Override
	public List<Iteration> listIterations(ProjectRef project) {
		return cache.get(List.of("listIterations", project), () -> List.copyOf(delegate.listIterations(project)));
	}

	@Override
	public Optional<Iteration> currentIteration(ProjectRef project, LocalDate today) {
		return listIterations(project).stream().filter(iteration -> iteration.contains(today)).findFirst();
	}
}
