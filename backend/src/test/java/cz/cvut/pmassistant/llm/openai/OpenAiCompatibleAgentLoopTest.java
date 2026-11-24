package cz.cvut.pmassistant.llm.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import cz.cvut.pmassistant.agent.AgentDefinition;
import cz.cvut.pmassistant.agent.AgentLoop;
import cz.cvut.pmassistant.agent.AgentRunRecorder;
import cz.cvut.pmassistant.agent.AgentRunRequest;
import cz.cvut.pmassistant.agent.AgentRunResult;
import cz.cvut.pmassistant.agent.RunContext;
import cz.cvut.pmassistant.agent.RunLimits;
import cz.cvut.pmassistant.agent.Tool;
import cz.cvut.pmassistant.agent.ToolOutcome;
import cz.cvut.pmassistant.llm.model.ToolSpec;
import cz.cvut.pmassistant.tenant.WorkspaceRef;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The whole path: agent loop, adapter, HTTP, and an OpenAI-style server that answers from a script. */
class OpenAiCompatibleAgentLoopTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String PATH = "/v1/chat/completions";

	@RegisterExtension
	static WireMockExtension server = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private static final String FINAL_ANSWER = """
			{"id":"chatcmpl-9fA4","object":"chat.completion","model":"gpt-test",
			 "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
			   "tool_calls":[{"id":"call_final","type":"function",
			     "function":{"name":"submit_result","arguments":"{\\"category\\":\\"bug\\"}"}}]}}],
			 "usage":{"prompt_tokens":260,"completion_tokens":15,"total_tokens":275,
			          "prompt_tokens_details":{"cached_tokens":200}}}""";

	private static Tool getIssue() {
		return new Tool() {
			@Override
			public ToolSpec spec() {
				return new ToolSpec("get_issue", "Reads an issue", JSON.readTree("""
						{"type":"object","properties":{"number":{"type":"integer"},"repo":{"type":"string"}},
						 "required":["number"]}"""), false);
			}

			@Override
			public ToolOutcome execute(JsonNode arguments, RunContext context) {
				return ToolOutcome.ok("Issue " + arguments.get("number").asInt() + ": Login fails");
			}
		};
	}

	private static AgentDefinition agent() {
		ToolSpec submit = new ToolSpec("submit_result", "Submit the final result", JSON.readTree("""
				{"type":"object","properties":{"category":{"type":"string","enum":["bug","feature"]}},
				 "required":["category"],"additionalProperties":false}"""), true);
		return new AgentDefinition("triage", "You triage issues.", List.of(getIssue()), submit, RunLimits.standard());
	}

	@Test
	void aToolCallThenTheFinalAnswerGoThroughTheWholePath() {
		server.stubFor(post(urlEqualTo(PATH)).inScenario("run").whenScenarioStateIs(STARTED)
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody(OpenAiFixtures.read("chat-tool-call.json")))
				.willSetStateTo("second"));
		server.stubFor(post(urlEqualTo(PATH)).inScenario("run").whenScenarioStateIs("second")
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody(FINAL_ANSWER)));
		OpenAiCompatibleLlmClient llm = new OpenAiCompatibleLlmClient(
				OpenAiCompatibleSettings.of(server.baseUrl() + "/v1", "key", "gpt-test"));
		WorkspaceRef workspace = new WorkspaceRef(UUID.randomUUID(), 11, Set.of(), null);
		RunContext context = RunContext.forNewRun(workspace, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

		AgentRunResult result = new AgentLoop(llm, AgentRunRecorder.none())
				.run(AgentRunRequest.manual(agent(), "Triage issue 7"), context);

		assertThat(result).isEqualTo(new AgentRunResult.Completed(JSON.readTree("{\"category\":\"bug\"}")));
		List<ServeEvent> calls = server.getAllServeEvents().reversed();
		assertThat(calls).hasSize(2);

		JsonNode first = JSON.readTree(calls.get(0).getRequest().getBodyAsString());
		assertThat(first.path("tools")).hasSize(2);
		assertThat(first.path("tools").get(1).path("function").path("name").asString()).isEqualTo("submit_result");
		assertThat(first.path("tools").get(1).path("function").path("strict").asBoolean()).isTrue();

		// the second request carries the model's call (with its id) and the tool's answer to that id
		JsonNode messages = JSON.readTree(calls.get(1).getRequest().getBodyAsString()).path("messages");
		assertThat(messages).extracting(m -> m.path("role").asString())
				.containsExactly("system", "user", "assistant", "tool");
		assertThat(messages.get(2).path("tool_calls").get(0).path("id").asString()).isEqualTo("call_abc123");
		assertThat(messages.get(3).path("tool_call_id").asString()).isEqualTo("call_abc123");
		assertThat(messages.get(3).path("content").asString()).isEqualTo("Issue 7: Login fails");
	}
}
