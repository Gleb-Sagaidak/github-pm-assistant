package cz.cvut.pmassistant.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import cz.cvut.pmassistant.github.model.Iteration;
import cz.cvut.pmassistant.github.model.ProjectItem;
import cz.cvut.pmassistant.github.model.ProjectRef;

class CachingGitHubProjectsReaderTest {

	private static final ProjectRef BOARD = new ProjectRef("cvut-team", 1);

	private final GitHubProjectsReader github = mock(GitHubProjectsReader.class);
	private final GitHubProjectsReader reader = new CachingGitHubProjectsReader(github, new RunScopedCache());

	@Test
	void itemsAreReadOncePerRun() {
		when(github.listItems(BOARD)).thenReturn(List.of(
				new ProjectItem("PVTI_1", 7, "t", null, null, "o/r", "Todo", null)));

		assertThat(reader.listItems(BOARD)).hasSize(1);
		assertThat(reader.listItems(BOARD)).hasSize(1);

		verify(github, times(1)).listItems(BOARD);
	}

	@Test
	void differentBoardsAreCachedApart() {
		reader.listItems(BOARD);
		reader.listItems(new ProjectRef("cvut-team", 2));
		reader.listItems(BOARD);

		verify(github, times(1)).listItems(BOARD);
		verify(github, times(1)).listItems(new ProjectRef("cvut-team", 2));
	}

	@Test
	void currentIterationIsDerivedFromTheCachedIterations() {
		when(github.listIterations(BOARD)).thenReturn(List.of(
				new Iteration("it-1", "Sprint 1", LocalDate.of(2026, 10, 26), 14),
				new Iteration("it-2", "Sprint 2", LocalDate.of(2026, 11, 9), 14)));

		assertThat(reader.currentIteration(BOARD, LocalDate.of(2026, 11, 12))).hasValueSatisfying(
				iteration -> assertThat(iteration.id()).isEqualTo("it-2"));
		assertThat(reader.currentIteration(BOARD, LocalDate.of(2026, 10, 30))).hasValueSatisfying(
				iteration -> assertThat(iteration.id()).isEqualTo("it-1"));
		assertThat(reader.currentIteration(BOARD, LocalDate.of(2027, 1, 1))).isEmpty();
		reader.listIterations(BOARD);

		verify(github, times(1)).listIterations(BOARD);
	}

	@Test
	void boardListsAreNotCachedBecauseOnlyOnboardingUsesThem() {
		reader.listProjects("cvut-team");
		reader.listProjects("cvut-team");

		verify(github, times(2)).listProjects("cvut-team");
	}
}
