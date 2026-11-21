package cz.cvut.pmassistant.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A repository label. {@code id} is the numeric GitHub id, which survives renames;
 * {@code description} may be null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Label(
		long id,
		String name,
		String color,
		String description) {
}
