package cz.cvut.pmassistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import cz.cvut.pmassistant.llm.model.Usage;

import tools.jackson.databind.JsonNode;

/**
 * Where the loop writes down what happens (FR7, NFR10): the start of a run, every model call and tool call, and
 * the end. The loop does not know how it is stored; the database implementation arrives with the persistence
 * layer, tests use an in-memory one.
 * <p>
 * Steps are numbered in one sequence per run, model calls and tool calls together.
 */
public interface AgentRunRecorder {

	void started(RunStart start);

	void llmStep(UUID runId, LlmStep step);

	void toolStep(UUID runId, ToolStep step);

	void finished(UUID runId, RunFinish finish);

	/** A recorder that keeps nothing. */
	static AgentRunRecorder none() {
		return new AgentRunRecorder() {
			@Override
			public void started(RunStart start) {
			}

			@Override
			public void llmStep(UUID runId, LlmStep step) {
			}

			@Override
			public void toolStep(UUID runId, ToolStep step) {
			}

			@Override
			public void finished(UUID runId, RunFinish finish) {
			}
		};
	}

	/**
	 * @param githubRepoId the repository the run is about, or null for a run over the whole workspace
	 * @param triggeredBy  the user who started it, or null for a webhook or schedule
	 * @param input        what the run was asked to do, as structured data; may be null
	 */
	record RunStart(UUID runId, UUID workspaceId, Long githubRepoId, UUID triggeredBy, String agent, String trigger,
			JsonNode input, String model, Instant startedAt) {
	}

	/** One model call; {@code text} is what the model wrote besides tool calls. */
	record LlmStep(int seq, Usage usage, Duration duration, String text) {
	}

	record ToolStep(int seq, String toolName, JsonNode arguments, String output, boolean isError,
			Duration duration) {
	}

	/**
	 * @param llmSteps      how many times the model was called
	 * @param usage         tokens over the whole run
	 * @param repeatedCalls how many tool calls were refused as repeats (a metric of the "going in circles" failure)
	 */
	record RunFinish(Instant finishedAt, AgentRunResult result, int llmSteps, Usage usage, int repeatedCalls) {
	}
}
