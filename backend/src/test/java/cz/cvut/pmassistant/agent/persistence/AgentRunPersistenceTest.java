package cz.cvut.pmassistant.agent.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import cz.cvut.pmassistant.agent.AgentDefinition;
import cz.cvut.pmassistant.agent.AgentLoop;
import cz.cvut.pmassistant.agent.AgentRunRecorder;
import cz.cvut.pmassistant.agent.AgentRunRequest;
import cz.cvut.pmassistant.agent.AgentRunResult;
import cz.cvut.pmassistant.agent.FailureReason;
import cz.cvut.pmassistant.agent.RunContext;
import cz.cvut.pmassistant.agent.RunLimits;
import cz.cvut.pmassistant.agent.Tool;
import cz.cvut.pmassistant.agent.ToolOutcome;
import cz.cvut.pmassistant.github.RunScopedCache;
import cz.cvut.pmassistant.llm.fake.ScriptedLlmClient;
import cz.cvut.pmassistant.llm.model.LlmResponse;
import cz.cvut.pmassistant.llm.model.ToolSpec;
import cz.cvut.pmassistant.llm.model.Usage;
import cz.cvut.pmassistant.tenant.WorkspaceRef;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs and steps in a real Postgres. The recorder commits independently of the test transaction, so the test
 * itself runs without one and cleans the tables afterwards.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ PostgresTestConfig.class, JpaAgentRunRecorder.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentRunPersistenceTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID OTHER_WORKSPACE = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

	@Autowired
	AgentRunRepository runs;
	@Autowired
	AgentStepRepository steps;
	@Autowired
	JpaAgentRunRecorder recorder;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager transactionManager;

	@AfterEach
	void cleanTables() {
		jdbc.execute("delete from agent_step");
		jdbc.execute("delete from agent_run");
	}

	private static JsonNode json(String text) {
		return JSON.readTree(text);
	}

	private static RunContext contextOf(UUID workspaceId) {
		WorkspaceRef workspace = new WorkspaceRef(workspaceId, 11, Set.of(), null);
		return new RunContext(UUID.randomUUID(), workspace, new RunScopedCache(),
				Clock.fixed(PostgresTestConfig.NOW, ZoneOffset.UTC));
	}

	private static Tool getIssue() {
		return new Tool() {
			@Override
			public ToolSpec spec() {
				return new ToolSpec("get_issue", "Reads an issue", json("{\"type\":\"object\"}"), false);
			}

			@Override
			public ToolOutcome execute(JsonNode arguments, RunContext context) {
				return ToolOutcome.ok("Issue 7: Login fails");
			}
		};
	}

	private static AgentDefinition agent() {
		return new AgentDefinition("triage", "You triage issues.", List.of(getIssue()),
				new ToolSpec("submit_result", "Final answer", json("{\"type\":\"object\"}"), true),
				RunLimits.standard());
	}

	private AgentRunResult runCompleted(RunContext context) {
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofToolCall("c1", "get_issue", json("{\"number\":7,\"nested\":{\"tags\":[\"a\",\"b\"]}}"),
						new Usage(100, 20, 60)),
				LlmResponse.ofToolCall("c2", "submit_result", json("{\"category\":\"bug\",\"labels\":[\"x\"]}"),
						new Usage(150, 30, 100)));
		AgentRunRequest request = new AgentRunRequest(agent(), "Triage issue 7", "WEBHOOK", null, 4242L,
				json("{\"issue\":7}"));
		return new AgentLoop(llm, recorder).run(request, context);
	}

	@Test
	void theMigrationCreatesTheTables() {
		Integer tables = jdbc.queryForObject("""
				select count(*) from information_schema.tables
				where table_name in ('agent_run', 'agent_step')""", Integer.class);

		assertThat(tables).isEqualTo(2);
	}

	@Test
	void aCompletedRunIsStoredWithItsTotals() {
		RunContext context = contextOf(WORKSPACE);

		runCompleted(context);

		AgentRun run = runs.findByIdAndWorkspaceId(context.runId(), WORKSPACE).orElseThrow();
		assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
		assertThat(run.getAgent()).isEqualTo("triage");
		assertThat(run.getTriggerSource()).isEqualTo("WEBHOOK");
		assertThat(run.getGithubRepoId()).isEqualTo(4242L);
		assertThat(run.getTriggeredBy()).isNull();
		assertThat(run.getModel()).isEqualTo("scripted");
		assertThat(run.getInput()).isEqualTo(json("{\"issue\":7}"));
		assertThat(run.getFinalOutput()).isEqualTo(json("{\"category\":\"bug\",\"labels\":[\"x\"]}"));
		assertThat(run.getSteps()).isEqualTo(2);
		assertThat(run.getInputTokens()).isEqualTo(250);
		assertThat(run.getOutputTokens()).isEqualTo(50);
		assertThat(run.getCacheReadTokens()).isEqualTo(160);
		assertThat(run.getRepeatedCalls()).isZero();
		assertThat(run.getStartedAt()).isEqualTo(PostgresTestConfig.NOW);
		assertThat(run.getFinishedAt()).isEqualTo(PostgresTestConfig.NOW);
		assertThat(run.getFailureReason()).isNull();
	}

	@Test
	void everyStepIsStoredInFullAndInOrder() {
		RunContext context = contextOf(WORKSPACE);

		runCompleted(context);

		List<AgentStep> stored = steps.findStepsOfRun(context.runId(), WORKSPACE);
		assertThat(stored).extracting(AgentStep::getSeq).containsExactly(1, 2, 3);
		assertThat(stored).extracting(AgentStep::getType).containsExactly(StepType.LLM, StepType.TOOL, StepType.LLM);

		AgentStep first = stored.get(0);
		assertThat(first.getInputTokens()).isEqualTo(100);
		assertThat(first.getOutputTokens()).isEqualTo(20);
		assertThat(first.getToolName()).isNull();

		AgentStep tool = stored.get(1);
		assertThat(tool.getToolName()).isEqualTo("get_issue");
		assertThat(tool.getArgs()).as("JSON survives the round trip, nesting included")
				.isEqualTo(json("{\"number\":7,\"nested\":{\"tags\":[\"a\",\"b\"]}}"));
		assertThat(tool.getOutput().asString()).isEqualTo("Issue 7: Login fails");
		assertThat(tool.isError()).isFalse();
		assertThat(tool.getInputTokens()).isNull();
	}

	@Test
	void aFailedRunKeepsItsReasonAndHasNoFinalOutput() {
		RunContext context = contextOf(WORKSPACE);
		ScriptedLlmClient llm = ScriptedLlmClient.of(LlmResponse.ofRefusal("No.", new Usage(5, 5, 0)));

		new AgentLoop(llm, recorder).run(AgentRunRequest.manual(agent(), "go"), context);

		AgentRun run = runs.findByIdAndWorkspaceId(context.runId(), WORKSPACE).orElseThrow();
		assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
		assertThat(run.getFailureReason()).isEqualTo(FailureReason.REFUSAL);
		assertThat(run.getFailureDetail()).contains("declined");
		assertThat(run.getFinalOutput()).isNull();
		assertThat(run.getTriggerSource()).isEqualTo("MANUAL");
	}

	@Test
	void aRunOfAnotherWorkspaceCannotBeReached() {
		RunContext context = contextOf(WORKSPACE);
		runCompleted(context);

		assertThat(runs.findByIdAndWorkspaceId(context.runId(), OTHER_WORKSPACE)).isEmpty();
		assertThat(steps.findStepsOfRun(context.runId(), OTHER_WORKSPACE)).isEmpty();
		assertThat(runs.findByWorkspaceIdOrderByStartedAtDesc(OTHER_WORKSPACE, PageRequest.of(0, 10))).isEmpty();
		assertThat(runs.findByWorkspaceIdOrderByStartedAtDesc(WORKSPACE, PageRequest.of(0, 10))).hasSize(1);
	}

	@Test
	void theWorkspaceListIsNewestFirstAndPaged() {
		for (int i = 0; i < 3; i++) {
			RunContext context = new RunContext(UUID.randomUUID(), new WorkspaceRef(WORKSPACE, 11, Set.of(), null),
					new RunScopedCache(), Clock.fixed(PostgresTestConfig.NOW.plus(Duration.ofMinutes(i)), ZoneOffset.UTC));
			new AgentLoop(ScriptedLlmClient.of(
					LlmResponse.ofToolCall("c", "submit_result", json("{}"), Usage.ZERO)), recorder)
					.run(AgentRunRequest.manual(agent(), "run " + i), context);
		}

		List<AgentRun> firstPage = runs.findByWorkspaceIdOrderByStartedAtDesc(WORKSPACE, PageRequest.of(0, 2));
		List<AgentRun> secondPage = runs.findByWorkspaceIdOrderByStartedAtDesc(WORKSPACE, PageRequest.of(1, 2));

		assertThat(firstPage).extracting(AgentRun::getStartedAt).containsExactly(
				PostgresTestConfig.NOW.plus(Duration.ofMinutes(2)), PostgresTestConfig.NOW.plus(Duration.ofMinutes(1)));
		assertThat(secondPage).extracting(AgentRun::getStartedAt).containsExactly(PostgresTestConfig.NOW);
	}

	@Test
	void aStepNumberCannotBeUsedTwiceInOneRun() {
		RunContext context = contextOf(WORKSPACE);
		runCompleted(context);

		assertThatThrownBy(() -> steps.save(AgentStep.llm(context.runId(),
				new AgentRunRecorder.LlmStep(1, Usage.ZERO, Duration.ZERO, "duplicate"), Instant.now())))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aStepNeedsARunThatExists() {
		assertThatThrownBy(() -> steps.save(AgentStep.llm(UUID.randomUUID(),
				new AgentRunRecorder.LlmStep(1, Usage.ZERO, Duration.ZERO, "orphan"), Instant.now())))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theTrailSurvivesARollbackOfTheCallersTransaction() {
		RunContext context = contextOf(WORKSPACE);
		TransactionTemplate caller = new TransactionTemplate(transactionManager);

		assertThatThrownBy(() -> caller.executeWithoutResult(status -> {
			runCompleted(context);
			throw new IllegalStateException("the caller fails afterwards");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(runs.findByIdAndWorkspaceId(context.runId(), WORKSPACE)).isPresent();
		assertThat(steps.findStepsOfRun(context.runId(), WORKSPACE)).hasSize(3);
	}

	@Test
	void finishingAnUnknownRunIsAnError() {
		AgentRunRecorder.RunFinish finish = new AgentRunRecorder.RunFinish(WORKSPACE, Instant.now(),
				new AgentRunResult.Completed(json("{}")), 0, Usage.ZERO, 0);

		assertThatThrownBy(() -> recorder.finished(UUID.randomUUID(), finish))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("was not started");
	}

	@Test
	void aFailedRunWithoutAReasonIsRejectedByTheDatabase() {
		RunContext context = contextOf(WORKSPACE);
		runCompleted(context);

		assertThatThrownBy(() -> jdbc.update("update agent_run set status = 'FAILED', failure_reason = null where id = ?",
				context.runId())).isInstanceOf(DataIntegrityViolationException.class);
	}
}
