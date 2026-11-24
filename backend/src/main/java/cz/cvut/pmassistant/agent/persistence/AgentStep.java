package cz.cvut.pmassistant.agent.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import cz.cvut.pmassistant.agent.AgentRunRecorder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;

/**
 * One step of a run: a call to the model or to a tool, stored in full (arguments, output, tokens, duration) so it
 * can be shown in the UI and exported for evaluation.
 */
@Entity
@Table(name = "agent_step")
public class AgentStep {

	@Id
	private UUID id;

	@Column(name = "run_id", nullable = false)
	private UUID runId;

	@Column(nullable = false)
	private int seq;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private StepType type;

	@Column(name = "tool_name")
	private String toolName;

	@JdbcTypeCode(SqlTypes.JSON)
	private JsonNode args;

	@JdbcTypeCode(SqlTypes.JSON)
	private JsonNode output;

	@Column(name = "is_error", nullable = false)
	private boolean error;

	@Column(name = "input_tokens")
	private Integer inputTokens;

	@Column(name = "output_tokens")
	private Integer outputTokens;

	@Column(name = "duration_ms", nullable = false)
	private int durationMs;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AgentStep() {
		// for JPA
	}

	public static AgentStep llm(UUID runId, AgentRunRecorder.LlmStep step, Instant createdAt) {
		AgentStep row = new AgentStep();
		row.id = UUID.randomUUID();
		row.runId = runId;
		row.seq = step.seq();
		row.type = StepType.LLM;
		row.output = step.text() == null || step.text().isEmpty() ? null : StringNode.valueOf(step.text());
		row.inputTokens = step.usage().inputTokens();
		row.outputTokens = step.usage().outputTokens();
		row.durationMs = millis(step.duration());
		row.createdAt = createdAt;
		return row;
	}

	public static AgentStep tool(UUID runId, AgentRunRecorder.ToolStep step, Instant createdAt) {
		AgentStep row = new AgentStep();
		row.id = UUID.randomUUID();
		row.runId = runId;
		row.seq = step.seq();
		row.type = StepType.TOOL;
		row.toolName = step.toolName();
		row.args = step.arguments();
		row.output = StringNode.valueOf(step.output());
		row.error = step.isError();
		row.durationMs = millis(step.duration());
		row.createdAt = createdAt;
		return row;
	}

	private static int millis(Duration duration) {
		return (int) Math.min(Integer.MAX_VALUE, duration.toMillis());
	}

	public UUID getId() {
		return id;
	}

	public UUID getRunId() {
		return runId;
	}

	public int getSeq() {
		return seq;
	}

	public StepType getType() {
		return type;
	}

	public String getToolName() {
		return toolName;
	}

	public JsonNode getArgs() {
		return args;
	}

	public JsonNode getOutput() {
		return output;
	}

	public boolean isError() {
		return error;
	}

	public Integer getInputTokens() {
		return inputTokens;
	}

	public Integer getOutputTokens() {
		return outputTokens;
	}

	public int getDurationMs() {
		return durationMs;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
