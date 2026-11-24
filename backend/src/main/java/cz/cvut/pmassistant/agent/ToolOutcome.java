package cz.cvut.pmassistant.agent;

import java.util.Objects;

/**
 * What a tool hands back to the model. A failed tool does not fail the run: its error text goes to the model
 * (as an error result), which may carry on without it.
 */
public record ToolOutcome(String content, boolean isError) {

	public ToolOutcome {
		Objects.requireNonNull(content, "content");
	}

	public static ToolOutcome ok(String content) {
		return new ToolOutcome(content, false);
	}

	public static ToolOutcome error(String message) {
		return new ToolOutcome(message, true);
	}
}
