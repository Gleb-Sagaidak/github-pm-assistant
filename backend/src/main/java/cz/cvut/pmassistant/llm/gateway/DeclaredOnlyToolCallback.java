package cz.cvut.pmassistant.llm.gateway;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import cz.cvut.pmassistant.llm.model.ToolSpec;

/**
 * Tells the model that a tool exists, and nothing more. Running tools is the job of the agent loop (it enforces
 * the limits, the schema checks and the recording), so this callback must never be executed by Spring AI.
 */
final class DeclaredOnlyToolCallback implements ToolCallback {

	private final ToolDefinition definition;

	DeclaredOnlyToolCallback(ToolSpec spec) {
		this.definition = ToolDefinition.builder()
				.name(spec.name())
				.description(spec.description())
				.inputSchema(spec.inputSchema().toString())
				.build();
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return definition;
	}

	@Override
	public String call(String toolInput) {
		throw new IllegalStateException("The tool " + definition.name()
				+ " is run by the agent loop, not by the model client");
	}
}
