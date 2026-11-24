package cz.cvut.pmassistant.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Keeps everything the loop reports, in order, so tests can look at it. */
final class RecordingRunRecorder implements AgentRunRecorder {

	RunStart start;
	RunFinish finish;
	final List<LlmStep> llmSteps = new ArrayList<>();
	final List<ToolStep> toolSteps = new ArrayList<>();
	/** "started", "llm", "tool", "finished" in the order they happened. */
	final List<String> events = new ArrayList<>();

	@Override
	public void started(RunStart start) {
		this.start = start;
		events.add("started");
	}

	@Override
	public void llmStep(UUID runId, LlmStep step) {
		llmSteps.add(step);
		events.add("llm");
	}

	@Override
	public void toolStep(UUID runId, ToolStep step) {
		toolSteps.add(step);
		events.add("tool");
	}

	@Override
	public void finished(UUID runId, RunFinish finish) {
		this.finish = finish;
		events.add("finished");
	}
}
