package cz.cvut.pmassistant.llm.model;

import java.util.Objects;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * What the model is told about a tool.
 *
 * @param inputSchema JSON Schema of the arguments (an object schema)
 * @param strict      ask the provider to guarantee that calls match the schema, where it can
 */
public record ToolSpec(String name, String description, JsonNode inputSchema, boolean strict) {

	/** The intersection of what common providers accept as a tool name. */
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	public ToolSpec {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(inputSchema, "inputSchema");
		if (!NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("Invalid tool name: " + name);
		}
		if (!inputSchema.isObject()) {
			throw new IllegalArgumentException("The input schema of " + name + " must be a JSON object");
		}
	}
}
