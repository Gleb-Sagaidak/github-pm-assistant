package cz.cvut.pmassistant.agent;

import static cz.cvut.pmassistant.agent.AgentTestSupport.TERMINAL;
import static cz.cvut.pmassistant.agent.AgentTestSupport.agent;
import static cz.cvut.pmassistant.agent.AgentTestSupport.context;
import static cz.cvut.pmassistant.agent.AgentTestSupport.json;
import static cz.cvut.pmassistant.agent.AgentTestSupport.terminal;
import static cz.cvut.pmassistant.agent.AgentTestSupport.tool;
import static cz.cvut.pmassistant.agent.AgentTestSupport.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import cz.cvut.pmassistant.llm.fake.ScriptedLlmClient;
import cz.cvut.pmassistant.llm.model.ContentPart;
import cz.cvut.pmassistant.llm.model.LlmResponse;
import cz.cvut.pmassistant.llm.model.ToolSpec;

/** The final answer must match the terminal tool's schema; the model gets exactly one chance to fix it. */
class AgentLoopFinalAnswerTest {

	private static final String SCHEMA = """
			{"type":"object",
			 "properties":{"category":{"type":"string","enum":["bug","feature","question"]}},
			 "required":["category"],
			 "additionalProperties":false}""";

	private final RecordingRunRecorder recorder = new RecordingRunRecorder();
	private final ToolSpec terminal = terminal(SCHEMA);

	private AgentRunResult run(ScriptedLlmClient llm, Tool... tools) {
		return new AgentLoop(llm, recorder).run(
				AgentRunRequest.manual(agent(RunLimits.standard(), terminal, tools), "go"), context());
	}

	private static LlmResponse submit(String id, String argumentsJson) {
		return LlmResponse.ofToolCall(id, TERMINAL, json(argumentsJson), usage(1, 1));
	}

	@Test
	void validArgumentsCompleteTheRun() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(submit("a", "{\"category\":\"bug\"}"));

		AgentRunResult result = run(llm);

		assertThat(result).isEqualTo(new AgentRunResult.Completed(json("{\"category\":\"bug\"}")));
		assertThat(recorder.toolSteps).isEmpty();
	}

	@Test
	void anInvalidAnswerIsReturnedToTheModelWhichMayCorrectIt() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				submit("a", "{\"category\":\"chore\"}"), submit("b", "{\"category\":\"feature\"}"));

		AgentRunResult result = run(llm);

		assertThat(result).isEqualTo(new AgentRunResult.Completed(json("{\"category\":\"feature\"}")));
		ContentPart.ToolResult complaint = (ContentPart.ToolResult) llm.requests().get(1).messages().get(2).parts().get(0);
		assertThat(complaint.toolCallId()).isEqualTo("a");
		assertThat(complaint.isError()).isTrue();
		assertThat(complaint.content()).contains(TERMINAL, "do not match its schema", "category", "corrected");
	}

	@Test
	void theRejectedAttemptIsRecordedAsAFailedToolStep() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				submit("a", "{\"category\":\"chore\"}"), submit("b", "{\"category\":\"bug\"}"));

		run(llm);

		assertThat(recorder.toolSteps).singleElement().satisfies(step -> {
			assertThat(step.toolName()).isEqualTo(TERMINAL);
			assertThat(step.isError()).isTrue();
			assertThat(step.arguments()).isEqualTo(json("{\"category\":\"chore\"}"));
		});
	}

	@Test
	void anInvalidAnswerTwiceFailsTheRun() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				submit("a", "{\"category\":\"chore\"}"), submit("b", "{\"category\":\"task\"}"));

		AgentRunResult result = run(llm);

		assertThat(result).isInstanceOfSatisfying(AgentRunResult.Failed.class, failed -> {
			assertThat(failed.reason()).isEqualTo(FailureReason.INVALID_FINAL_ANSWER);
			assertThat(failed.detail()).contains("twice", "category");
		});
		assertThat(llm.callCount()).isEqualTo(2);
		assertThat(recorder.toolSteps).hasSize(2);
	}

	@Test
	void theSecondChanceIsPerRunNotPerTurn() {
		Tool getIssue = tool("get_issue", args -> ToolOutcome.ok("x"));
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				submit("a", "{\"category\":\"chore\"}"),
				LlmResponse.ofToolCall("r", "get_issue", json("{}"), usage(1, 1)),
				submit("b", "{\"category\":\"task\"}"));

		AgentRunResult result = run(llm, getIssue);

		assertThat(result).isInstanceOfSatisfying(AgentRunResult.Failed.class,
				failed -> assertThat(failed.reason()).isEqualTo(FailureReason.INVALID_FINAL_ANSWER));
	}

	@Test
	void missingAndExtraPropertiesAreInvalidToo() {
		ScriptedLlmClient llm = ScriptedLlmClient.of(submit("a", "{}"),
				submit("b", "{\"category\":\"bug\",\"severity\":9}"));

		AgentRunResult result = run(llm);

		assertThat(result).isInstanceOf(AgentRunResult.Failed.class);
		assertThat(llm.requests().get(1).messages().get(2).parts().get(0))
				.isInstanceOfSatisfying(ContentPart.ToolResult.class,
						complaint -> assertThat(complaint.content()).contains("category"));
	}

	@Test
	void readCallsInTheSameTurnAreStillAnsweredWhenTheFinalAnswerIsRejected() {
		Tool getIssue = tool("get_issue", args -> ToolOutcome.ok("issue text"));
		ScriptedLlmClient llm = ScriptedLlmClient.of(
				LlmResponse.ofToolCalls(usage(1, 1),
						new ContentPart.ToolCall("r", "get_issue", json("{}")),
						new ContentPart.ToolCall("a", TERMINAL, json("{\"category\":\"chore\"}"))),
				submit("b", "{\"category\":\"bug\"}"));

		AgentRunResult result = run(llm, getIssue);

		assertThat(result).isInstanceOf(AgentRunResult.Completed.class);
		assertThat(llm.requests().get(1).messages().get(2).parts()).hasSize(2);
	}

	@Test
	void anAgentWhoseSchemaCannotBeUsedCannotBeDefined() {
		assertThatThrownBy(() -> agent(RunLimits.standard(),
				terminal("{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/missing\"}}}")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Invalid JSON Schema");
	}
}
