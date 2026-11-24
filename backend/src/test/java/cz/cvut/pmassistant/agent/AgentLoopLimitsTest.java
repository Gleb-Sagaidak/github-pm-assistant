package cz.cvut.pmassistant.agent;

import static cz.cvut.pmassistant.agent.AgentTestSupport.START;
import static cz.cvut.pmassistant.agent.AgentTestSupport.TERMINAL;
import static cz.cvut.pmassistant.agent.AgentTestSupport.agent;
import static cz.cvut.pmassistant.agent.AgentTestSupport.context;
import static cz.cvut.pmassistant.agent.AgentTestSupport.json;
import static cz.cvut.pmassistant.agent.AgentTestSupport.tool;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import cz.cvut.pmassistant.llm.fake.ScriptedLlmClient;
import cz.cvut.pmassistant.llm.model.LlmResponse;
import cz.cvut.pmassistant.llm.model.Usage;

/** The hard limits of a run (NFR8): time, tokens (steps are covered with the loop itself). */
class AgentLoopLimitsTest {

	private final RecordingRunRecorder recorder = new RecordingRunRecorder();
	private final TestClock clock = new TestClock(START);

	private AgentRunResult run(ScriptedLlmClient llm, AgentDefinition agent) {
		return new AgentLoop(llm, recorder).run(AgentRunRequest.manual(agent, "go"), context(clock));
	}

	private static LlmResponse readCall(String id, int input, int output) {
		return LlmResponse.ofToolCall(id, "get_issue", json("{\"id\":\"" + id + "\"}"), new Usage(input, output, 0));
	}

	private static void assertFailed(AgentRunResult result, FailureReason reason) {
		assertThat(result).isInstanceOfSatisfying(AgentRunResult.Failed.class,
				failed -> assertThat(failed.reason()).isEqualTo(reason));
	}

	@Test
	void aRunThatTakesTooLongFailsBeforeTheNextModelCall() {
		Tool slow = tool("get_issue", args -> {
			clock.advance(Duration.ofSeconds(61));
			return ToolOutcome.ok("x");
		});
		ScriptedLlmClient llm = ScriptedLlmClient.of(readCall("a", 1, 1), readCall("b", 1, 1));

		AgentRunResult result = run(llm, agent(RunLimits.standard().withMaxDuration(Duration.ofSeconds(60)), slow));

		assertFailed(result, FailureReason.TIME_LIMIT);
		assertThat(llm.callCount()).isEqualTo(1);
		assertThat(((AgentRunResult.Failed) result).detail()).contains("61 s", "60 s");
		assertThat(recorder.finish.result()).isEqualTo(result);
	}

	@Test
	void takingExactlyTheTimeLimitIsStillAllowed() {
		Tool tool = tool("get_issue", args -> {
			clock.advance(Duration.ofSeconds(60));
			return ToolOutcome.ok("x");
		});
		ScriptedLlmClient llm = ScriptedLlmClient.of(readCall("a", 1, 1),
				LlmResponse.ofToolCall("z", TERMINAL, json("{}"), Usage.ZERO));

		AgentRunResult result = run(llm, agent(RunLimits.standard().withMaxDuration(Duration.ofSeconds(60)), tool));

		assertThat(result).isInstanceOf(AgentRunResult.Completed.class);
	}

	@Test
	void timeIsMeasuredFromTheStartOfTheRun() {
		clock.advance(Duration.ofHours(5));
		ScriptedLlmClient llm = ScriptedLlmClient.of(LlmResponse.ofToolCall("z", TERMINAL, json("{}"), Usage.ZERO));

		AgentRunResult result = run(llm, agent(RunLimits.standard().withMaxDuration(Duration.ofSeconds(60))));

		assertThat(result).isInstanceOf(AgentRunResult.Completed.class);
	}

	@Test
	void aRunThatUsesTooManyTokensFailsAndItsToolCallsAreNotRun() {
		int[] executed = { 0 };
		Tool counted = tool("get_issue", args -> {
			executed[0]++;
			return ToolOutcome.ok("x");
		});
		ScriptedLlmClient llm = ScriptedLlmClient.of(readCall("a", 60, 50), readCall("b", 1, 1));

		AgentRunResult result = run(llm, agent(RunLimits.standard().withMaxTotalTokens(100), counted));

		assertFailed(result, FailureReason.TOKEN_LIMIT);
		assertThat(executed[0]).isZero();
		assertThat(llm.callCount()).isEqualTo(1);
		assertThat(((AgentRunResult.Failed) result).detail()).contains("110", "100");
		assertThat(recorder.finish.usage()).isEqualTo(new Usage(60, 50, 0));
		assertThat(recorder.llmSteps).hasSize(1);
	}

	@Test
	void tokensAddUpAcrossTheSteps() {
		Tool getIssue = tool("get_issue", args -> ToolOutcome.ok("x"));
		ScriptedLlmClient llm = ScriptedLlmClient.of(readCall("a", 30, 10), readCall("b", 30, 10),
				readCall("c", 30, 10));

		AgentRunResult result = run(llm, agent(RunLimits.standard().withMaxTotalTokens(100), getIssue));

		assertFailed(result, FailureReason.TOKEN_LIMIT);
		assertThat(llm.callCount()).as("40 + 40 is within 100, 40 + 40 + 40 is not").isEqualTo(3);
	}

	@Test
	void usingExactlyTheTokenLimitIsStillAllowed() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofToolCall("z", TERMINAL, json("{}"), new Usage(60, 40, 0)));

		AgentRunResult result = run(llm, agent(RunLimits.standard().withMaxTotalTokens(100)));

		assertThat(result).isInstanceOf(AgentRunResult.Completed.class);
	}

	@Test
	void aFinalAnswerThatBlowsTheTokenBudgetIsStillAFailure() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofToolCall("z", TERMINAL, json("{}"), new Usage(90, 20, 0)));

		AgentRunResult result = run(llm, agent(RunLimits.standard().withMaxTotalTokens(100)));

		assertFailed(result, FailureReason.TOKEN_LIMIT);
	}
}
