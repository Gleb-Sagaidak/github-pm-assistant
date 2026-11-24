package cz.cvut.pmassistant.agent;

import java.util.Objects;

import tools.jackson.databind.JsonNode;

/** How a run ended. */
public sealed interface AgentRunResult {

	/** The model called the terminal tool with valid arguments. */
	record Completed(JsonNode finalArguments) implements AgentRunResult {

		public Completed {
			Objects.requireNonNull(finalArguments, "finalArguments");
		}
	}

	record Failed(FailureReason reason, String detail) implements AgentRunResult {

		public Failed {
			Objects.requireNonNull(reason, "reason");
			Objects.requireNonNull(detail, "detail");
		}
	}
}
