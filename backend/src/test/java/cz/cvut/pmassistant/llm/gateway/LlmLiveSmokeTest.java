package cz.cvut.pmassistant.llm.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import cz.cvut.pmassistant.LiveConfig;
import cz.cvut.pmassistant.agent.AgentDefinition;
import cz.cvut.pmassistant.agent.AgentLoop;
import cz.cvut.pmassistant.agent.AgentRunRecorder;
import cz.cvut.pmassistant.agent.AgentRunRequest;
import cz.cvut.pmassistant.agent.AgentRunResult;
import cz.cvut.pmassistant.agent.RunContext;
import cz.cvut.pmassistant.agent.RunLimits;
import cz.cvut.pmassistant.agent.Tool;
import cz.cvut.pmassistant.agent.ToolOutcome;
import cz.cvut.pmassistant.llm.model.LlmMessage;
import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.LlmResponse;
import cz.cvut.pmassistant.llm.model.StopReason;
import cz.cvut.pmassistant.llm.model.ToolSpec;
import cz.cvut.pmassistant.tenant.WorkspaceRef;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual check against a real model through Spring AI: one plain question, and one agent run in which the model has
 * to call a toy tool and then the terminal tool. It costs a few hundred tokens. It is NOT part of the normal build
 * (tag {@code live}); run it with
 *
 * <pre>
 * ./mvnw test -Plive -Dtest=LlmLiveSmokeTest
 * </pre>
 *
 * Settings come from environment variables or the repository's {@code .env} file, the same ones the application
 * uses:
 * <ul>
 * <li>{@code LLM_MODEL}: the model name (without it the test is skipped);</li>
 * <li>{@code LLM_BASE_URL}: default {@code https://api.openai.com/v1}; for Ollama
 * {@code http://localhost:11434/v1};</li>
 * <li>{@code LLM_API_KEY}: leave empty for servers that need none;</li>
 * <li>{@code APP_LLM_MAX_TOKENS_PARAMETER}: {@code max_completion_tokens} for OpenAI's newer models, default
 * {@code max_tokens}.</li>
 * </ul>
 */
@Tag("live")
class LlmLiveSmokeTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static GatewayLlmClient client;

	@BeforeAll
	static void connect() {
		LiveConfig config = new LiveConfig();
		assumeTrue(config.get("LLM_MODEL") != null, "LLM_MODEL is not set: live LLM test skipped");
		String baseUrl = config.get("LLM_BASE_URL") != null ? config.get("LLM_BASE_URL") : "https://api.openai.com/v1";
		String model = config.require("LLM_MODEL");
		boolean completionTokens = "max_completion_tokens".equals(config.get("APP_LLM_MAX_TOKENS_PARAMETER"));
		System.out.println("[live] base URL " + baseUrl + ", model " + model + ", key "
				+ (config.get("LLM_API_KEY") == null ? "<none>" : "<set>") + ", limit field "
				+ (completionTokens ? "max_completion_tokens" : "max_tokens"));
		OpenAiChatModel chatModel = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
				.baseUrl(baseUrl)
				.apiKey(config.get("LLM_API_KEY") == null ? "" : config.get("LLM_API_KEY"))
				.model(model)
				.timeout(Duration.ofSeconds(120))
				.maxRetries(2)
				.build()).build();
		client = new GatewayLlmClient(chatModel, model, new OpenAiErrorClassifier(),
				completionTokens ? OutputLimit.maxCompletionTokens() : OutputLimit.maxTokens());
	}

	@Test
	void theModelAnswersAPlainQuestion() {
		LlmResponse response = client.chat(new LlmRequest("Answer in one word.",
				List.of(LlmMessage.user("What is the opposite of 'ping'? Reply with a single word.")), List.of(), 200));

		System.out.println("[live] answer: " + response.text() + " | stop: " + response.stopReason() + " | usage: "
				+ response.usage());
		assertThat(response.text()).isNotBlank();
		assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void theModelCallsAToolAndThenTheTerminalTool() {
		AtomicInteger timeCalls = new AtomicInteger();
		Tool getTime = new Tool() {
			@Override
			public ToolSpec spec() {
				return new ToolSpec("get_time", "Returns the current date and time in UTC.",
						JSON.readTree("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"), false);
			}

			@Override
			public ToolOutcome execute(JsonNode arguments, RunContext context) {
				timeCalls.incrementAndGet();
				return ToolOutcome.ok("2026-11-24T12:00:00Z");
			}
		};
		ToolSpec submit = new ToolSpec("submit_answer", "Submit the final answer. Call it exactly once, at the end.",
				JSON.readTree("""
						{"type":"object",
						 "properties":{"time":{"type":"string","description":"The time returned by get_time"}},
						 "required":["time"],"additionalProperties":false}"""), true);
		AgentDefinition agent = new AgentDefinition("live-smoke",
				"You are a test agent. Use the tools you are given and finish by calling submit_answer.",
				List.of(getTime), submit, RunLimits.standard().withMaxSteps(6).withMaxTotalTokens(20_000));
		AtomicReference<AgentRunRecorder.RunFinish> finish = new AtomicReference<>();
		AgentRunRecorder recorder = new AgentRunRecorder() {
			@Override
			public void started(RunStart start) {
			}

			@Override
			public void llmStep(UUID runId, LlmStep step) {
			}

			@Override
			public void toolStep(UUID runId, ToolStep step) {
				System.out.println("[live] tool " + step.toolName() + " " + step.arguments() + " -> " + step.output());
			}

			@Override
			public void finished(UUID runId, RunFinish result) {
				finish.set(result);
			}
		};
		RunContext context = RunContext.forNewRun(new WorkspaceRef(UUID.randomUUID(), 1, Set.of(), null),
				Clock.systemUTC());

		AgentRunResult result = new AgentLoop(client, recorder).run(AgentRunRequest.manual(agent,
				"Find out the current time with get_time, then submit it with submit_answer."), context);

		System.out.println("[live] result: " + result);
		System.out.println("[live] model calls: " + finish.get().llmSteps() + " | usage: " + finish.get().usage()
				+ " | repeated calls: " + finish.get().repeatedCalls());
		assertThat(result).isInstanceOf(AgentRunResult.Completed.class);
		assertThat(timeCalls.get()).as("the model used the tool").isGreaterThanOrEqualTo(1);
		assertThat(((AgentRunResult.Completed) result).finalArguments().path("time").asString()).contains("2026");
	}
}
