package cz.cvut.pmassistant.agent;

import cz.cvut.pmassistant.llm.model.ToolSpec;

import tools.jackson.databind.JsonNode;

/**
 * Something an agent can do besides answering: in practice a <em>read</em> (an issue, labels, a board). The
 * agent's only way to act is its terminal tool, which the loop handles itself and which is not a {@code Tool}.
 */
public interface Tool {

	/** What the model is told about this tool. */
	ToolSpec spec();

	default String name() {
		return spec().name();
	}

	/**
	 * Runs the tool. Expected failures (not found, a repository outside the workspace) are returned as
	 * {@link ToolOutcome#error}; an exception that escapes is turned into one by the loop.
	 */
	ToolOutcome execute(JsonNode arguments, RunContext context);
}
