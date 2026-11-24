package cz.cvut.pmassistant.llm.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Loads JSON answers from {@code src/test/resources/openai/}. */
final class OpenAiFixtures {

	private OpenAiFixtures() {
	}

	static String read(String name) {
		String path = "/openai/" + name;
		try (InputStream in = OpenAiFixtures.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalArgumentException("Missing test fixture " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot read test fixture " + path, e);
		}
	}
}
