package cz.cvut.pmassistant.github.model;

import java.time.LocalDate;

/** An iteration (sprint) of a Projects v2 board; it spans {@code durationDays} days from {@code startDate}. */
public record Iteration(String id, String title, LocalDate startDate, int durationDays) {
}
