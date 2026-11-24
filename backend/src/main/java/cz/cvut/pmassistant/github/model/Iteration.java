package cz.cvut.pmassistant.github.model;

import java.time.LocalDate;

/**
 * An iteration (sprint) of a Projects v2 board. It spans {@code durationDays} days from {@code startDate}, so
 * the last day is {@code startDate + durationDays - 1} and the next iteration starts the day after it.
 */
public record Iteration(String id, String title, LocalDate startDate, int durationDays) {

	/** The last day of the iteration (inclusive). */
	public LocalDate endDate() {
		return startDate.plusDays(durationDays - 1L);
	}

	public boolean contains(LocalDate day) {
		return !day.isBefore(startDate) && !day.isAfter(endDate());
	}
}
