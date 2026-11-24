package cz.cvut.pmassistant.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import cz.cvut.pmassistant.llm.model.ToolSpec;

/**
 * Describes one agent: its prompt, the tools it may read with, and the terminal tool whose call is its final
 * answer. The system prompt and the tool list are fixed per agent (no dates or ids), so a provider that caches
 * prompt prefixes can reuse them across steps and runs.
 *
 * @param terminalTool the spec of the tool that ends the run, e.g. {@code submit_triage_proposal}; the loop
 *                     validates its arguments against the schema and never executes anything for it
 */
public record AgentDefinition(String name, String systemPrompt, List<Tool> tools, ToolSpec terminalTool,
		RunLimits limits) {

	public AgentDefinition {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(systemPrompt, "systemPrompt");
		Objects.requireNonNull(terminalTool, "terminalTool");
		Objects.requireNonNull(limits, "limits");
		tools = List.copyOf(tools);
		ToolRegistry registry = new ToolRegistry(tools);
		if (registry.find(terminalTool.name()).isPresent()) {
			throw new IllegalArgumentException("The terminal tool name is also used by a read tool: "
					+ terminalTool.name());
		}
	}

	public ToolRegistry registry() {
		return new ToolRegistry(tools);
	}

	/** What the model is offered: the read tools in order, then the terminal tool. */
	public List<ToolSpec> allSpecs() {
		List<ToolSpec> specs = new ArrayList<>(registry().specs());
		specs.add(terminalTool);
		return List.copyOf(specs);
	}
}
