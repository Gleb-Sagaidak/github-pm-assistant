package cz.cvut.pmassistant.agent.persistence;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cz.cvut.pmassistant.agent.AgentRunRecorder;

/**
 * Stores what the agent loop reports in the database. Every call commits on its own, whatever transaction the
 * caller is in: a step is on record even if the caller later rolls back, so the trail of a run is never lost
 * with it (NFR10).
 */
@Component
public class JpaAgentRunRecorder implements AgentRunRecorder {

	private final AgentRunRepository runs;
	private final AgentStepRepository steps;
	private final Clock clock;

	public JpaAgentRunRecorder(AgentRunRepository runs, AgentStepRepository steps, Clock clock) {
		this.runs = runs;
		this.steps = steps;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void started(RunStart start) {
		runs.save(AgentRun.started(start));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void llmStep(UUID runId, LlmStep step) {
		steps.save(AgentStep.llm(runId, step, clock.instant()));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void toolStep(UUID runId, ToolStep step) {
		steps.save(AgentStep.tool(runId, step, clock.instant()));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void finished(UUID runId, RunFinish finish) {
		AgentRun run = runs.findByIdAndWorkspaceId(runId, finish.workspaceId())
				.orElseThrow(() -> new IllegalStateException("Run " + runId + " was not started"));
		run.finish(finish);
		runs.save(run);
	}
}
