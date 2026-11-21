package cz.cvut.pmassistant.github;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import cz.cvut.pmassistant.github.model.Iteration;
import cz.cvut.pmassistant.github.model.ProjectItem;
import cz.cvut.pmassistant.github.model.ProjectRef;
import cz.cvut.pmassistant.github.model.ProjectSummary;

/**
 * Read-only access to Projects v2 (GraphQL). A board belongs to an account and may contain items from
 * several repositories, so it is addressed by {@link ProjectRef}, not by a repository.
 */
public interface GitHubProjectsReader {

	/** Boards of an organization, for the "choose a board" step of onboarding. */
	List<ProjectSummary> listProjects(String ownerLogin);

	List<ProjectItem> listItems(ProjectRef project);

	List<Iteration> listIterations(ProjectRef project);

	/** The iteration that contains {@code today}; empty if none does. */
	Optional<Iteration> currentIteration(ProjectRef project, LocalDate today);
}
