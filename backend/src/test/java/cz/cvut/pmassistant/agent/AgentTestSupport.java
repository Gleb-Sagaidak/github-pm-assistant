package cz.cvut.pmassistant.agent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import cz.cvut.pmassistant.llm.model.ContentPart;
import cz.cvut.pmassistant.llm.model.ToolSpec;
import cz.cvut.pmassistant.llm.model.Usage;
import cz.cvut.pmassistant.tenant.WorkspaceRef;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small builders shared by the agent tests. */
final class AgentTestSupport {

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String TERMINAL = "submit_result";
	static final Instant START = Instant.parse("2026-11-24T10:00:00Z");

	private AgentTestSupport() {
	}

	static JsonNode json(String text) {
		return JSON.readTree(text);
	}

	static Usage usage(int input, int output) {
		return new Usage(input, output, 0);
	}

	static ContentPart.ToolCall call(String id, String name, String argumentsJson) {
		return new ContentPart.ToolCall(id, name, json(argumentsJson));
	}

	/** A read tool whose behaviour the test supplies. */
	static Tool tool(String name, Function<JsonNode, ToolOutcome> behaviour) {
		return new Tool() {
			@Override
			public ToolSpec spec() {
				return new ToolSpec(name, "Test tool " + name, json("{\"type\":\"object\"}"), false);
			}

			@Override
			public ToolOutcome execute(JsonNode arguments, RunContext context) {
				return behaviour.apply(arguments);
			}
		};
	}

	/** A read tool whose arguments must match the given JSON Schema. */
	static Tool toolWithSchema(String name, String schemaJson, Function<JsonNode, ToolOutcome> behaviour) {
		return new Tool() {
			@Override
			public ToolSpec spec() {
				return new ToolSpec(name, "Test tool " + name, json(schemaJson), false);
			}

			@Override
			public ToolOutcome execute(JsonNode arguments, RunContext context) {
				return behaviour.apply(arguments);
			}
		};
	}

	/** The terminal tool, with the given JSON Schema for its arguments. */
	static ToolSpec terminal(String schemaJson) {
		return new ToolSpec(TERMINAL, "Submit the final result", json(schemaJson), true);
	}

	static ToolSpec anyObjectTerminal() {
		return terminal("{\"type\":\"object\"}");
	}

	static AgentDefinition agent(RunLimits limits, ToolSpec terminal, Tool... tools) {
		return new AgentDefinition("test-agent", "You are a test agent.", List.of(tools), terminal, limits);
	}

	static AgentDefinition agent(RunLimits limits, Tool... tools) {
		return agent(limits, anyObjectTerminal(), tools);
	}

	static AgentDefinition agent(Tool... tools) {
		return agent(RunLimits.standard(), tools);
	}

	static WorkspaceRef workspace() {
		return new WorkspaceRef(UUID.fromString("00000000-0000-0000-0000-000000000042"), 11, Set.of(), null);
	}

	static RunContext context(Clock clock) {
		return RunContext.forNewRun(workspace(), clock);
	}

	static RunContext context() {
		return context(Clock.fixed(START, ZoneOffset.UTC));
	}
}
