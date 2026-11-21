package cz.cvut.pmassistant.github.model;

import java.util.Objects;

/**
 * Identifies a Projects v2 board. A board belongs to an account (organization), not to a repository.
 */
public record ProjectRef(String ownerLogin, int number) {

	public ProjectRef {
		Objects.requireNonNull(ownerLogin, "ownerLogin");
	}
}
