package cz.cvut.pmassistant.agent;

import static cz.cvut.pmassistant.agent.AgentTestSupport.TERMINAL;
import static cz.cvut.pmassistant.agent.AgentTestSupport.agent;
import static cz.cvut.pmassistant.agent.AgentTestSupport.context;
import static cz.cvut.pmassistant.agent.AgentTestSupport.json;
import static cz.cvut.pmassistant.agent.AgentTestSupport.toolWithSchema;
import static cz.cvut.pmassistant.agent.AgentTestSupport.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import cz.cvut.pmassistant.llm.fake.ScriptedLlmClient;
import cz.cvut.pmassistant.llm.model.ContentPart;
import cz.cvut.pmassistant.llm.model.LlmResponse;

/** Arguments of read tools are checked against the tool's schema before the tool runs. */
class AgentLoopToolArgumentsTest {

	private static final String SCHEMA = """
			{"type":"object",
			 "properties":{"number":{"type":"integer","minimum":1}},
			 "required":["number"],
			 "additionalProperties":false}""";

	private final RecordingRunRecorder recorder = new RecordingRunRecorder();
	private final AtomicInteger executions = new AtomicInteger();
	private final Tool getIssue = toolWithSchema("get_issue", SCHEMA, args -> {
		executions.incrementAndGet();
		return ToolOutcome.ok("issue " + args.get("number").asInt());
	});

	private ScriptedLlmClient run(String... argumentsOfEachCall) {
		LlmResponse[] script = new LlmResponse[argumentsOfEachCall.length + 1];
		for (int i = 0; i < argumentsOfEachCall.length; i++) {
			script[i] = LlmResponse.ofToolCall("c" + i, "get_issue", json(argumentsOfEachCall[i]), usage(1, 1));
		}
		script[argumentsOfEachCall.length] = LlmResponse.ofToolCall("end", TERMINAL, json("{}"), usage(1, 1));
		ScriptedLlmClient llm = ScriptedLlmClient.of((Object[]) script);
		new AgentLoop(llm, recorder).run(AgentRunRequest.manual(agent(getIssue), "go"), context());
		return llm;
	}

	private static ContentPart.ToolResult answerTo(ScriptedLlmClient llm, int request) {
		var messages = llm.requests().get(request).messages();
		return (ContentPart.ToolResult) messages.get(messages.size() - 1).parts().get(0);
	}

	@Test
	void validArgumentsRunTheTool() {
		ScriptedLlmClient llm = run("{\"number\":7}");

		assertThat(executions).hasValue(1);
		assertThat(answerTo(llm, 1)).isEqualTo(new ContentPart.ToolResult("c0", "issue 7", false));
	}

	@Test
	void argumentsThatDoNotFitAreRefusedWithAnExplanationAndTheToolIsNotRun() {
		ScriptedLlmClient llm = run("{\"number\":\"seven\"}");

		assertThat(executions).hasValue(0);
		ContentPart.ToolResult answer = answerTo(llm, 1);
		assertThat(answer.isError()).isTrue();
		assertThat(answer.content()).contains("get_issue", "do not match its schema", "number", "corrected");
	}

	@Test
	void aMissingRequiredArgumentIsRefused() {
		ScriptedLlmClient llm = run("{}");

		assertThat(executions).hasValue(0);
		assertThat(answerTo(llm, 1).content()).contains("number");
	}

	@Test
	void anExtraArgumentIsRefused() {
		run("{\"number\":7,\"verbose\":true}");

		assertThat(executions).hasValue(0);
	}

	@Test
	void argumentsThatAreNotAnObjectAreRefused() {
		// what an adapter hands over when a model sends text that is not valid JSON
		ScriptedLlmClient llm = run("\"{number: 7\"");

		assertThat(executions).hasValue(0);
		assertThat(answerTo(llm, 1).isError()).isTrue();
	}

	@Test
	void theModelCanCorrectItselfAndTheCorrectedCallRuns() {
		ScriptedLlmClient llm = run("{\"number\":0}", "{\"number\":7}");

		assertThat(executions).hasValue(1);
		assertThat(answerTo(llm, 1).isError()).isTrue();
		assertThat(answerTo(llm, 2)).isEqualTo(new ContentPart.ToolResult("c1", "issue 7", false));
	}

	@Test
	void refusedCallsAreRecordedAndAreNotCountedAsRepeats() {
		run("{\"number\":0}", "{\"number\":0}", "{\"number\":0}", "{\"number\":7}");

		assertThat(recorder.toolSteps).hasSize(4);
		assertThat(recorder.toolSteps).extracting(AgentRunRecorder.ToolStep::isError)
				.containsExactly(true, true, true, false);
		assertThat(recorder.finish.repeatedCalls()).isZero();
		assertThat(executions).hasValue(1);
	}

	@Test
	void aToolWhoseSchemaCannotBeUsedCannotBeDefined() {
		Tool broken = toolWithSchema("broken", "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/nope\"}}}",
				args -> ToolOutcome.ok("x"));

		assertThatThrownBy(() -> agent(broken)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Invalid JSON Schema");
	}
}
