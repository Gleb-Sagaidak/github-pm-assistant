package cz.cvut.pmassistant.github;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Loads JSON fixtures from {@code src/test/resources/github/}. */
public final class Fixtures {

	private Fixtures() {
	}

	public static String read(String name) {
		String path = "/github/" + name;
		try (InputStream in = Fixtures.class.getResourceAsStream(path)) {
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
