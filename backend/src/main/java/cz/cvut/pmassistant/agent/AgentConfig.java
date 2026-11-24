package cz.cvut.pmassistant.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cz.cvut.pmassistant.llm.LlmClient;

/** Makes the agent loop available: one loop, reading from the configured model and writing to the run records. */
@Configuration(proxyBeanMethods = false)
public class AgentConfig {

	@Bean
	AgentLoop agentLoop(LlmClient llm, AgentRunRecorder recorder) {
		return new AgentLoop(llm, recorder);
	}
}
