package cz.cvut.pmassistant.github.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class IterationTest {

	private final Iteration sprint = new Iteration("it-2", "Sprint 2", LocalDate.of(2026, 11, 9), 14);

	@Test
	void lastDayIsTheStartPlusDurationMinusOne() {
		assertThat(sprint.endDate()).isEqualTo(LocalDate.of(2026, 11, 22));
	}

	@Test
	void containsBothBoundaryDays() {
		assertThat(sprint.contains(LocalDate.of(2026, 11, 9))).isTrue();
		assertThat(sprint.contains(LocalDate.of(2026, 11, 22))).isTrue();
	}

	@Test
	void doesNotContainTheDaysAround() {
		assertThat(sprint.contains(LocalDate.of(2026, 11, 8))).isFalse();
		assertThat(sprint.contains(LocalDate.of(2026, 11, 23))).isFalse();
	}

	@Test
	void consecutiveIterationsDoNotOverlap() {
		Iteration next = new Iteration("it-3", "Sprint 3", LocalDate.of(2026, 11, 23), 14);

		assertThat(sprint.contains(next.startDate())).isFalse();
		assertThat(next.contains(sprint.endDate())).isFalse();
	}
}
