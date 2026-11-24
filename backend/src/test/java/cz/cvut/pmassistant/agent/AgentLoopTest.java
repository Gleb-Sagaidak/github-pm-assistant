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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import cz.cvut.pmassistant.llm.LlmClient;
import cz.cvut.pmassistant.llm.LlmUnavailableException;
import cz.cvut.pmassistant.llm.fake.ScriptedLlmClient;
import cz.cvut.pmassistant.llm.model.ContentPart;
import cz.cvut.pmassistant.llm.model.LlmMessage;
import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.LlmResponse;
import cz.cvut.pmassistant.llm.model.Role;
import cz.cvut.pmassistant.llm.model.StopReason;
import cz.cvut.pmassistant.llm.model.ToolSpec;
import cz.cvut.pmassistant.llm.model.Usage;

class AgentLoopTest {

	private final RecordingRunRecorder recorder = new RecordingRunRecorder();

	private AgentRunResult run(ScriptedLlmClient llm, AgentDefinition agent) {
		return new AgentLoop(llm, recorder).run(AgentRunRequest.manual(agent, "Triage issue 7"), context());
	}

	private static LlmResponse submit(String argumentsJson) {
		return LlmResponse.ofToolCall("final", TERMINAL, json(argumentsJson), usage(10, 5));
	}

	@Test
	void aToolCallThenTheFinalAnswerCompletesTheRun() {
		Tool getIssue = tool("get_issue", args -> ToolOutcome.ok("issue #" + args.get("number").asInt()));
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofToolCall("c1", "get_issue", json("{\"number\":7}"), usage(100, 20)),
				submit("{\"category\":\"bug\"}"));

		AgentRunResult result = run(llm, agent(getIssue));

		assertThat(result).isEqualTo(new AgentRunResult.Completed(json("{\"category\":\"bug\"}")));
		assertThat(recorder.events).containsExactly("started", "llm", "tool", "llm", "finished");
		assertThat(recorder.llmSteps).extracting(AgentRunRecorder.LlmStep::seq).containsExactly(1, 3);
		AgentRunRecorder.ToolStep toolStep = recorder.toolSteps.get(0);
		assertThat(toolStep.seq()).isEqualTo(2);
		assertThat(toolStep.toolName()).isEqualTo("get_issue");
		assertThat(toolStep.arguments()).isEqualTo(json("{\"number\":7}"));
		assertThat(toolStep.output()).isEqualTo("issue #7");
		assertThat(toolStep.isError()).isFalse();
		assertThat(recorder.finish.llmSteps()).isEqualTo(2);
		assertThat(recorder.finish.usage()).isEqualTo(usage(110, 25));
	}

	@Test
	void theModelGetsTheToolResultInTheNextRequest() {
		Tool getIssue = tool("get_issue", args -> ToolOutcome.ok("issue text"));
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofToolCall("c1", "get_issue", json("{}"), usage(1, 1)), submit("{}"));

		run(llm, agent(getIssue));

		List<LlmMessage> second = llm.requests().get(1).messages();
		assertThat(second).extracting(LlmMessage::role).containsExactly(Role.USER, Role.ASSISTANT, Role.USER);
		assertThat(second.get(0).parts()).containsExactly(new ContentPart.Text("Triage issue 7"));
		assertThat(second.get(1).parts()).containsExactly(call("c1", "get_issue", "{}"));
		assertThat(second.get(2).parts()).containsExactly(new ContentPart.ToolResult("c1", "issue text", false));
	}

	@Test
	void everyRequestCarriesThePromptTheToolsAndTheOutputLimit() {
		Tool getIssue = tool("get_issue", args -> ToolOutcome.ok("x"));
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofToolCall("c1", "get_issue", json("{}"), usage(1, 1)), submit("{}"));
		AgentDefinition agent = agent(getIssue);

		run(llm, agent);

		for (LlmRequest request : llm.requests()) {
			assertThat(request.system()).isEqualTo("You are a test agent.");
			assertThat(request.tools()).extracting(ToolSpec::name).containsExactly("get_issue", TERMINAL);
			assertThat(request.maxTokens()).isEqualTo(agent.limits().maxOutputTokens());
		}
	}

	@Test
	void parallelCallsAreAnsweredTogetherInOneMessageInOrder() {
		Tool first = tool("first", args -> ToolOutcome.ok("one"));
		Tool second = tool("second", args -> ToolOutcome.ok("two"));
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofToolCalls(usage(1, 1), call("a", "first", "{}"), call("b", "second", "{}")),
				submit("{}"));

		run(llm, agent(first, second));

		List<LlmMessage> messages = llm.requests().get(1).messages();
		assertThat(messages).hasSize(3);
		assertThat(messages.get(2).parts()).containsExactly(
				new ContentPart.ToolResult("a", "one", false), new ContentPart.ToolResult("b", "two", false));
		assertThat(recorder.toolSteps).extracting(AgentRunRecorder.ToolStep::toolName).containsExactly("first", "second");
	}

	@Test
	void anUnknownToolIsAnErrorForTheModelAndTheRunContinues() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofToolCall("c1", "delete_everything", json("{}"), usage(1, 1)), submit("{}"));

		AgentRunResult result = run(llm, agent(tool("get_issue", args -> ToolOutcome.ok("x"))));

		assertThat(result).isInstanceOf(AgentRunResult.Completed.class);
		ContentPart.ToolResult answer = (ContentPart.ToolResult) llm.requests().get(1).messages().get(2).parts().get(0);
		assertThat(answer.isError()).isTrue();
		assertThat(answer.content()).contains("Unknown tool: delete_everything", "get_issue", TERMINAL);
		assertThat(recorder.toolSteps.get(0).isError()).isTrue();
	}

	@Test
	void aToolThatThrowsIsAnErrorForTheModelAndTheRunContinues() {
		Tool broken = tool("get_issue", args -> {
			throw new IllegalStateException("GitHub is down");
		});
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofToolCall("c1", "get_issue", json("{}"), usage(1, 1)), submit("{}"));

		AgentRunResult result = run(llm, agent(broken));

		assertThat(result).isInstanceOf(AgentRunResult.Completed.class);
		ContentPart.ToolResult answer = (ContentPart.ToolResult) llm.requests().get(1).messages().get(2).parts().get(0);
		assertThat(answer.isError()).isTrue();
		assertThat(answer.content()).contains("get_issue failed", "IllegalStateException", "GitHub is down");
	}

	@Test
	void aToolErrorOutcomeIsPassedOnAsAnError() {
		Tool notFound = tool("get_issue", args -> ToolOutcome.error("Issue 99 not found"));
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofToolCall("c1", "get_issue", json("{}"), usage(1, 1)), submit("{}"));

		run(llm, agent(notFound));

		assertThat(llm.requests().get(1).messages().get(2).parts())
				.containsExactly(new ContentPart.ToolResult("c1", "Issue 99 not found", true));
	}

	@Test
	void aRefusalFailsTheRun() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(LlmResponse.ofRefusal("I can't help with that", usage(5, 5)));

		AgentRunResult result = run(llm, agent());

		assertThat(result).isInstanceOfSatisfying(AgentRunResult.Failed.class,
				failed -> assertThat(failed.reason()).isEqualTo(FailureReason.REFUSAL));
		assertThat(llm.callCount()).isEqualTo(1);
		assertThat(recorder.finish.result()).isEqualTo(result);
	}

	@Test
	void anAnswerCutOffAtTheTokenLimitFailsTheRun() {
		LlmResponse cutOff = new LlmResponse(List.of(new ContentPart.Text("partial ans")), StopReason.MAX_TOKENS,
				usage(5, 4096));
		ScriptedLlmClient llm = ScriptedLlmClient.of(cutOff);

		AgentRunResult result = run(llm, agent());

		assertThat(result).isInstanceOfSatisfying(AgentRunResult.Failed.class,
				failed -> assertThat(failed.reason()).isEqualTo(FailureReason.MAX_TOKENS));
	}

	@Test
	void aModelThatStopsWithoutAnsweringGetsOneReminder() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofText("I think it is a bug.", usage(5, 5)), submit("{\"category\":\"bug\"}"));

		AgentRunResult result = run(llm, agent());

		assertThat(result).isInstanceOf(AgentRunResult.Completed.class);
		LlmMessage reminder = llm.requests().get(1).messages().get(2);
		assertThat(reminder.role()).isEqualTo(Role.USER);
		assertThat(((ContentPart.Text) reminder.parts().get(0)).text()).contains(TERMINAL);
	}

	@Test
	void aModelThatStopsWithoutAnsweringTwiceFailsTheRun() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofText("hmm", usage(5, 5)), LlmResponse.ofText("still thinking", usage(5, 5)));

		AgentRunResult result = run(llm, agent());

		assertThat(result).isInstanceOfSatisfying(AgentRunResult.Failed.class,
				failed -> assertThat(failed.reason()).isEqualTo(FailureReason.NO_FINAL_ANSWER));
		assertThat(llm.callCount()).isEqualTo(2);
	}

	@Test
	void anEmptyAnswerDoesNotBreakTheConversation() {
		LlmResponse empty = new LlmResponse(List.of(), StopReason.END_TURN, usage(5, 0));
		ScriptedLlmClient llm = ScriptedLlmClient.of(empty, submit("{}"));

		AgentRunResult result = run(llm, agent());

		assertThat(result).isInstanceOf(AgentRunResult.Completed.class);
		assertThat(llm.requests().get(1).messages()).extracting(LlmMessage::role)
				.containsExactly(Role.USER, Role.ASSISTANT, Role.USER);
	}

	@Test
	void theStepLimitStopsAModelThatKeepsCallingTools() {
		Tool getIssue = tool("get_issue", args -> ToolOutcome.ok("x"));
		AtomicInteger id = new AtomicInteger();
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofToolCall("c" + id.incrementAndGet(), "get_issue", json("{\"n\":1}"), usage(1, 1)),
				LlmResponse.ofToolCall("c" + id.incrementAndGet(), "get_issue", json("{\"n\":2}"), usage(1, 1)),
				LlmResponse.ofToolCall("c" + id.incrementAndGet(), "get_issue", json("{\"n\":3}"), usage(1, 1)));

		AgentRunResult result = run(llm, agent(RunLimits.standard().withMaxSteps(2), getIssue));

		assertThat(result).isInstanceOfSatisfying(AgentRunResult.Failed.class, failed -> {
			assertThat(failed.reason()).isEqualTo(FailureReason.STEP_LIMIT);
			assertThat(failed.detail()).contains("2 steps");
		});
		assertThat(llm.callCount()).isEqualTo(2);
	}

	@Test
	void anUnavailableModelFailsTheRunWithTheReason() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				new LlmUnavailableException(LlmUnavailableException.Reason.RATE_LIMITED, "429 from provider"));

		AgentRunResult result = run(llm, agent());

		assertThat(result).isInstanceOfSatisfying(AgentRunResult.Failed.class, failed -> {
			assertThat(failed.reason()).isEqualTo(FailureReason.LLM_UNAVAILABLE);
			assertThat(failed.detail()).contains("RATE_LIMITED", "429 from provider");
		});
		assertThat(recorder.events).containsExactly("started", "finished");
	}

	@Test
	void opaqueBlocksComeBackInTheNextRequestUnchanged() {
		ContentPart.Opaque reasoning = new ContentPart.Opaque("reasoning", json("{\"signature\":\"abc\",\"n\":[1,2]}"));
		LlmResponse withReasoning = new LlmResponse(
				List.of(reasoning, new ContentPart.Text("Let me look."), call("c1", "get_issue", "{}")),
				StopReason.TOOL_USE, usage(1, 1));
		ScriptedLlmClient llm = ScriptedLlmClient.of(withReasoning, submit("{}"));

		run(llm, agent(tool("get_issue", args -> ToolOutcome.ok("x"))));

		LlmMessage assistant = llm.requests().get(1).messages().get(1);
		assertThat(assistant.role()).isEqualTo(Role.ASSISTANT);
		assertThat(assistant.parts()).containsExactlyElementsOf(withReasoning.parts());
	}

	@Test
	void theTerminalCallEndsTheRunWithoutRunningCallsAfterIt() {
		AtomicInteger executed = new AtomicInteger();
		Tool getIssue = tool("get_issue", args -> {
			executed.incrementAndGet();
			return ToolOutcome.ok("x");
		});
		ScriptedLlmClient llm = ScriptedLlmClient.of(LlmResponse.ofToolCalls(usage(1, 1),
				call("a", TERMINAL, "{\"category\":\"bug\"}"), call("b", "get_issue", "{}")));

		AgentRunResult result = run(llm, agent(getIssue));

		assertThat(result).isEqualTo(new AgentRunResult.Completed(json("{\"category\":\"bug\"}")));
		assertThat(executed).hasValue(0);
	}

	@Test
	void readCallsBeforeTheTerminalCallInTheSameTurnAreStillRun() {
		AtomicInteger executed = new AtomicInteger();
		Tool getIssue = tool("get_issue", args -> {
			executed.incrementAndGet();
			return ToolOutcome.ok("x");
		});
		ScriptedLlmClient llm = ScriptedLlmClient.of(LlmResponse.ofToolCalls(usage(1, 1),
				call("a", "get_issue", "{}"), call("b", TERMINAL, "{}")));

		run(llm, agent(getIssue));

		assertThat(executed).hasValue(1);
		assertThat(recorder.toolSteps).hasSize(1);
	}

	@Test
	void theRunIsRecordedWithItsOrigin() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(submit("{}"));
		RunContext context = context();

		new AgentLoop(llm, recorder).run(AgentRunRequest.manual(agent(), "go"), context);

		assertThat(recorder.start.runId()).isEqualTo(context.runId());
		assertThat(recorder.start.workspaceId()).isEqualTo(context.workspace().workspaceId());
		assertThat(recorder.start.agent()).isEqualTo("test-agent");
		assertThat(recorder.start.trigger()).isEqualTo("MANUAL");
		assertThat(recorder.start.model()).isEqualTo("scripted");
		assertThat(recorder.start.startedAt()).isEqualTo(AgentTestSupport.START);
		assertThat(recorder.finish.finishedAt()).isEqualTo(AgentTestSupport.START);
	}

	@Test
	void anUnexpectedExceptionIsRecordedAsAFailedRunAndRethrown() {
		LlmClient broken = new LlmClient() {
			@Override
			public LlmResponse chat(LlmRequest request) {
				return null;
			}

			@Override
			public String model() {
				return "broken";
			}
		};

		assertThatThrownBy(() -> new AgentLoop(broken, recorder)
				.run(AgentRunRequest.manual(agent(), "go"), context())).isInstanceOf(NullPointerException.class);

		assertThat(recorder.finish.result()).isInstanceOfSatisfying(AgentRunResult.Failed.class,
				failed -> assertThat(failed.reason()).isEqualTo(FailureReason.INTERNAL_ERROR));
	}

	@Test
	void usageOfEveryCallIsSummed() {
		Tool getIssue = tool("get_issue", args -> ToolOutcome.ok("x"));
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofToolCall("c1", "get_issue", json("{}"), new Usage(100, 10, 60)),
				LlmResponse.ofToolCall("c2", TERMINAL, json("{}"), new Usage(150, 20, 100)));

		run(llm, agent(getIssue));

		assertThat(recorder.finish.usage()).isEqualTo(new Usage(250, 30, 160));
	}
}
