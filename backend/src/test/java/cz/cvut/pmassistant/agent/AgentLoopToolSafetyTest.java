package cz.cvut.pmassistant.agent;

import static cz.cvut.pmassistant.agent.AgentTestSupport.TERMINAL;
import static cz.cvut.pmassistant.agent.AgentTestSupport.agent;
import static cz.cvut.pmassistant.agent.AgentTestSupport.call;
import static cz.cvut.pmassistant.agent.AgentTestSupport.context;
import static cz.cvut.pmassistant.agent.AgentTestSupport.json;
import static cz.cvut.pmassistant.agent.AgentTestSupport.tool;
import static cz.cvut.pmassistant.agent.AgentTestSupport.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import cz.cvut.pmassistant.llm.fake.ScriptedLlmClient;
import cz.cvut.pmassistant.llm.model.ContentPart;
import cz.cvut.pmassistant.llm.model.LlmResponse;

/** Going in circles and hanging tools: what the loop does about them. */
class AgentLoopToolSafetyTest {

	private final RecordingRunRecorder recorder = new RecordingRunRecorder();
	private final AtomicInteger executions = new AtomicInteger();
	private final Tool getIssue = tool("get_issue", args -> {
		executions.incrementAndGet();
		return ToolOutcome.ok("issue text");
	});

	private AgentRunResult run(ScriptedLlmClient llm, AgentDefinition agent) {
		return new AgentLoop(llm, recorder).run(AgentRunRequest.manual(agent, "go"), context());
	}

	private static LlmResponse readCall(String id, String argumentsJson) {
		return LlmResponse.ofToolCall(id, "get_issue", json(argumentsJson), usage(1, 1));
	}

	private static LlmResponse submit() {
		return LlmResponse.ofToolCall("end", TERMINAL, json("{}"), usage(1, 1));
	}

	private static ContentPart.ToolResult resultOf(ScriptedLlmClient llm, int request) {
		List<ContentPart> parts = llm.requests().get(request).messages().get(llm.requests().get(request).messages().size() - 1)
				.parts();
		return (ContentPart.ToolResult) parts.get(0);
	}

	@Test
	void theThirdIdenticalCallIsRefusedAndCounted() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(readCall("1", "{\"n\":7}"), readCall("2", "{\"n\":7}"),
				readCall("3", "{\"n\":7}"), submit());

		AgentRunResult result = run(llm, agent(getIssue));

		assertThat(result).isInstanceOf(AgentRunResult.Completed.class);
		assertThat(executions).as("the first two are run, the third is not").hasValue(2);
		ContentPart.ToolResult refusal = resultOf(llm, 3);
		assertThat(refusal.isError()).isTrue();
		assertThat(refusal.content()).contains("already called get_issue", "earlier result");
		assertThat(recorder.finish.repeatedCalls()).isEqualTo(1);
		assertThat(recorder.toolSteps).hasSize(3);
		assertThat(recorder.toolSteps.get(2).isError()).isTrue();
	}

	@Test
	void everyFurtherIdenticalCallIsRefusedAndCounted() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(readCall("1", "{}"), readCall("2", "{}"), readCall("3", "{}"),
				readCall("4", "{}"), submit());

		run(llm, agent(getIssue));

		assertThat(executions).hasValue(2);
		assertThat(recorder.finish.repeatedCalls()).isEqualTo(2);
	}

	@Test
	void callsWithDifferentArgumentsAreNotRepeats() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(readCall("1", "{\"n\":1}"), readCall("2", "{\"n\":2}"),
				readCall("3", "{\"n\":3}"), readCall("4", "{\"n\":4}"), submit());

		run(llm, agent(getIssue));

		assertThat(executions).hasValue(4);
		assertThat(recorder.finish.repeatedCalls()).isZero();
	}

	@Test
	void theKeyOrderOfTheArgumentsDoesNotHideARepeat() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(readCall("1", "{\"repo\":\"a\",\"n\":7}"),
				readCall("2", "{\"n\":7,\"repo\":\"a\"}"), readCall("3", "{ \"repo\": \"a\", \"n\": 7 }"), submit());

		run(llm, agent(getIssue));

		assertThat(executions).hasValue(2);
		assertThat(recorder.finish.repeatedCalls()).isEqualTo(1);
	}

	@Test
	void repeatsInsideOneTurnAreCountedToo() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofToolCalls(usage(1, 1), call("a", "get_issue", "{}"), call("b", "get_issue", "{}"),
						call("c", "get_issue", "{}")),
				submit());

		run(llm, agent(getIssue));

		assertThat(executions).hasValue(2);
		assertThat(recorder.finish.repeatedCalls()).isEqualTo(1);
	}

	@Test
	void differentToolsWithTheSameArgumentsAreNotRepeats() {
		Tool other = tool("list_comments", args -> ToolOutcome.ok("comments"));
		ScriptedLlmClient llm = ScriptedLlmClient.of(readCall("1", "{}"),
				LlmResponse.ofToolCall("2", "list_comments", json("{}"), usage(1, 1)), readCall("3", "{}"),
				LlmResponse.ofToolCall("4", "list_comments", json("{}"), usage(1, 1)), submit());

		run(llm, agent(getIssue, other));

		assertThat(recorder.finish.repeatedCalls()).isZero();
	}

	@Test
	void aToolThatTakesTooLongIsAbandonedAndTheModelIsToldSo() throws Exception {
		CountDownLatch interrupted = new CountDownLatch(1);
		Tool stuck = tool("get_issue", args -> {
			try {
				Thread.sleep(10_000);
			}
			catch (InterruptedException e) {
				interrupted.countDown();
			}
			return ToolOutcome.ok("too late");
		});
		ScriptedLlmClient llm = ScriptedLlmClient.of(readCall("1", "{}"), submit());
		long begin = System.nanoTime();

		AgentRunResult result = run(llm, agent(RunLimits.standard().withToolTimeout(Duration.ofMillis(200)), stuck));

		assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(5));
		assertThat(result).isInstanceOf(AgentRunResult.Completed.class);
		ContentPart.ToolResult answer = resultOf(llm, 1);
		assertThat(answer.isError()).isTrue();
		assertThat(answer.content()).contains("get_issue", "timed out", "200 ms");
		assertThat(interrupted.await(2, TimeUnit.SECONDS)).as("the stuck tool was interrupted").isTrue();
	}

	@Test
	void aToolThatFinishesInTimeIsNotAffected() {
		Tool quick = tool("get_issue", args -> ToolOutcome.ok("fast"));
		ScriptedLlmClient llm = ScriptedLlmClient.of(readCall("1", "{}"), submit());

		run(llm, agent(RunLimits.standard().withToolTimeout(Duration.ofSeconds(5)), quick));

		assertThat(resultOf(llm, 1)).isEqualTo(new ContentPart.ToolResult("1", "fast", false));
	}

	@Test
	void aToolThatReturnsNothingIsAnError() {
		Tool nothing = tool("get_issue", args -> null);
		ScriptedLlmClient llm = ScriptedLlmClient.of(readCall("1", "{}"), submit());

		run(llm, agent(nothing));

		ContentPart.ToolResult answer = resultOf(llm, 1);
		assertThat(answer.isError()).isTrue();
		assertThat(answer.content()).contains("returned nothing");
	}

	@Test
	void anErrorThrownByAToolIsNotSwallowed() {
		Tool broken = tool("get_issue", args -> {
			throw new OutOfMemoryError("simulated");
		});
		ScriptedLlmClient llm = ScriptedLlmClient.of(readCall("1", "{}"), submit());

		assertThatThrownBy(() -> run(llm, agent(broken))).isInstanceOf(OutOfMemoryError.class);
	}

	@Test
	void interruptingTheRunStopsItAndKeepsTheInterruptFlag() throws Exception {
		CountDownLatch toolStarted = new CountDownLatch(1);
		Tool slow = tool("get_issue", args -> {
			toolStarted.countDown();
			try {
				Thread.sleep(10_000);
			}
			catch (InterruptedException e) {
				// the loop cancels the tool when the run is interrupted
			}
			return ToolOutcome.ok("x");
		});
		ScriptedLlmClient llm = ScriptedLlmClient.of(readCall("1", "{}"), submit());
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicReference<Boolean> flagKept = new AtomicReference<>();
		Thread runner = new Thread(() -> {
			try {
				run(llm, agent(RunLimits.standard().withToolTimeout(Duration.ofSeconds(30)), slow));
			}
			catch (Throwable t) {
				thrown.set(t);
				flagKept.set(Thread.currentThread().isInterrupted());
			}
		});
		runner.start();
		assertThat(toolStarted.await(2, TimeUnit.SECONDS)).isTrue();

		runner.interrupt();
		runner.join(5_000);

		assertThat(thrown.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("Interrupted");
		assertThat(flagKept.get()).isTrue();
		assertThat(recorder.finish.result()).isInstanceOfSatisfying(AgentRunResult.Failed.class,
				failed -> assertThat(failed.reason()).isEqualTo(FailureReason.INTERNAL_ERROR));
	}
}
