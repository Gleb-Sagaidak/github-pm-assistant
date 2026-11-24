package cz.cvut.pmassistant.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import cz.cvut.pmassistant.llm.model.ToolSpec;

/** The tools of one agent, looked up by the name the model uses. */
public final class ToolRegistry {

	private final Map<String, Tool> byName = new LinkedHashMap<>();

	public ToolRegistry(List<Tool> tools) {
		for (Tool tool : tools) {
			if (byName.putIfAbsent(tool.name(), tool) != null) {
				throw new IllegalArgumentException("Two tools are called " + tool.name());
			}
		}
	}

	public Optional<Tool> find(String name) {
		return Optional.ofNullable(byName.get(name));
	}

	/** The specs in registration order; the order stays the same between runs so prompts stay cacheable. */
	public List<ToolSpec> specs() {
		return byName.values().stream().map(Tool::spec).toList();
	}

	public int size() {
		return byName.size();
	}
}
