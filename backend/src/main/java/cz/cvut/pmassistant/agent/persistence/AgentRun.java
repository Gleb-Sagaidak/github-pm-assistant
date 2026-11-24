package cz.cvut.pmassistant.agent.persistence;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import cz.cvut.pmassistant.agent.AgentRunRecorder;
import cz.cvut.pmassistant.agent.AgentRunResult;
import cz.cvut.pmassistant.agent.FailureReason;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tools.jackson.databind.JsonNode;

/** One run of an agent, with its totals. Its steps are {@link AgentStep}s. */
@Entity
@Table(name = "agent_run")
public class AgentRun {

	@Id
	private UUID id;

	@Column(name = "workspace_id", nullable = false)
	private UUID workspaceId;

	@Column(name = "github_repo_id")
	private Long githubRepoId;

	@Column(name = "triggered_by")
	private UUID triggeredBy;

	@Column(nullable = false)
	private String agent;

	@Column(name = "trigger_source", nullable = false)
	private String triggerSource;

	@JdbcTypeCode(SqlTypes.JSON)
	private JsonNode input;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private RunStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "failure_reason")
	private FailureReason failureReason;

	@Column(name = "failure_detail")
	private String failureDetail;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	@Column(nullable = false)
	private int steps;

	@Column(name = "input_tokens", nullable = false)
	private int inputTokens;

	@Column(name = "output_tokens", nullable = false)
	private int outputTokens;

	@Column(name = "cache_read_tokens", nullable = false)
	private int cacheReadTokens;

	@Column(name = "repeated_calls", nullable = false)
	private int repeatedCalls;

	@Column(nullable = false)
	private String model;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "final_output")
	private JsonNode finalOutput;

	protected AgentRun() {
		// for JPA
	}

	/** A run that has just begun. */
	public static AgentRun started(AgentRunRecorder.RunStart start) {
		AgentRun run = new AgentRun();
		run.id = start.runId();
		run.workspaceId = start.workspaceId();
		run.githubRepoId = start.githubRepoId();
		run.triggeredBy = start.triggeredBy();
		run.agent = start.agent();
		run.triggerSource = start.trigger();
		run.input = start.input();
		run.status = RunStatus.RUNNING;
		run.startedAt = start.startedAt();
		run.model = start.model();
		return run;
	}

	/** Records how the run ended; a failed run keeps its reason and has no final output. */
	public void finish(AgentRunRecorder.RunFinish finish) {
		this.finishedAt = finish.finishedAt();
		this.steps = finish.llmSteps();
		this.inputTokens = finish.usage().inputTokens();
		this.outputTokens = finish.usage().outputTokens();
		this.cacheReadTokens = finish.usage().cacheReadTokens();
		this.repeatedCalls = finish.repeatedCalls();
		if (finish.result() instanceof AgentRunResult.Completed completed) {
			this.status = RunStatus.COMPLETED;
			this.finalOutput = completed.finalArguments();
		}
		else if (finish.result() instanceof AgentRunResult.Failed failed) {
			this.status = RunStatus.FAILED;
			this.failureReason = failed.reason();
			this.failureDetail = failed.detail();
		}
	}

	public UUID getId() {
		return id;
	}

	public UUID getWorkspaceId() {
		return workspaceId;
	}

	public Long getGithubRepoId() {
		return githubRepoId;
	}

	public UUID getTriggeredBy() {
		return triggeredBy;
	}

	public String getAgent() {
		return agent;
	}

	public String getTriggerSource() {
		return triggerSource;
	}

	public JsonNode getInput() {
		return input;
	}

	public RunStatus getStatus() {
		return status;
	}

	public FailureReason getFailureReason() {
		return failureReason;
	}

	public String getFailureDetail() {
		return failureDetail;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public int getSteps() {
		return steps;
	}

	public int getInputTokens() {
		return inputTokens;
	}

	public int getOutputTokens() {
		return outputTokens;
	}

	public int getCacheReadTokens() {
		return cacheReadTokens;
	}

	public int getRepeatedCalls() {
		return repeatedCalls;
	}

	public String getModel() {
		return model;
	}

	public JsonNode getFinalOutput() {
		return finalOutput;
	}
}
