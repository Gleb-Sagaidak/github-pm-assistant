package cz.cvut.pmassistant.agent;

import java.util.Objects;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * A request to run an agent once.
 *
 * @param userMessage  the first user message. Anything that changes between runs (today's date, the issue to
 *                     look at) goes here and not into the system prompt, which must stay constant so prompt
 *                     caching works.
 * @param trigger      what started the run, e.g. {@code MANUAL}, {@code WEBHOOK}, {@code SCHEDULE}
 * @param triggeredBy  the user who started it, or null
 * @param githubRepoId the repository it is about, or null
 * @param input        structured form of the request, kept with the run; may be null
 */
public record AgentRunRequest(AgentDefinition agent, String userMessage, String trigger, UUID triggeredBy,
		Long githubRepoId, JsonNode input) {

	public AgentRunRequest {
		Objects.requireNonNull(agent, "agent");
		Objects.requireNonNull(userMessage, "userMessage");
		Objects.requireNonNull(trigger, "trigger");
	}

	public static AgentRunRequest manual(AgentDefinition agent, String userMessage) {
		return new AgentRunRequest(agent, userMessage, "MANUAL", null, null, null);
	}
}
