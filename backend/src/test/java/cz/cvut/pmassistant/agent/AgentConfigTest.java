package cz.cvut.pmassistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cz.cvut.pmassistant.llm.LlmClient;
import cz.cvut.pmassistant.llm.fake.ScriptedLlmClient;

class AgentConfigTest {

	@Configuration(proxyBeanMethods = false)
	static class Collaborators {

		@Bean
		LlmClient llm() {
			return ScriptedLlmClient.of();
		}

		@Bean
		AgentRunRecorder recorder() {
			return AgentRunRecorder.none();
		}
	}

	@Test
	void theLoopIsBuiltFromThePortAndTheRecorder() {
		new ApplicationContextRunner().withUserConfiguration(Collaborators.class, AgentConfig.class)
				.run(context -> assertThat(context.getBean(AgentLoop.class)).isNotNull());
	}
}
