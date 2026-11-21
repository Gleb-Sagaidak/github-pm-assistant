package cz.cvut.pmassistant.github.model;

import java.util.Locale;

/** Value of the {@code state} query parameter when listing issues. */
public enum IssueStateFilter {
	OPEN, CLOSED, ALL;

	public String queryValue() {
		return name().toLowerCase(Locale.ROOT);
	}
}
