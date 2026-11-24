package cz.cvut.pmassistant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings for the live smoke test, taken from environment variables, or from {@code KEY=VALUE} lines of the
 * {@code .env} file in the repository root (never committed) when a variable is not set.
 */
public final class LiveConfig {

	private static final List<Path> ENV_FILES = List.of(Path.of("../.env"), Path.of(".env"));

	private final Map<String, String> fileValues = new HashMap<>();

	public LiveConfig() {
		for (Path file : ENV_FILES) {
			if (Files.isRegularFile(file)) {
				readFile(file);
			}
		}
	}

	/** The value, or null if it is set neither in the environment nor in a {@code .env} file. */
	public String get(String name) {
		String fromEnvironment = System.getenv(name);
		if (fromEnvironment != null && !fromEnvironment.isBlank()) {
			return fromEnvironment;
		}
		return fileValues.get(name);
	}

	public String require(String name) {
		String value = get(name);
		if (value == null) {
			throw new IllegalStateException("Live smoke test needs " + name + " (environment variable or .env)");
		}
		return value;
	}

	/** A path from the settings; relative paths are tried as given and then from the repository root. */
	public Path path(String name) {
		Path path = Path.of(require(name));
		if (!Files.exists(path) && Files.exists(Path.of("..").resolve(path))) {
			return Path.of("..").resolve(path);
		}
		return path;
	}

	private void readFile(Path file) {
		try {
			for (String line : Files.readAllLines(file)) {
				String trimmed = line.strip();
				int equals = trimmed.indexOf('=');
				if (trimmed.isEmpty() || trimmed.startsWith("#") || equals < 1) {
					continue;
				}
				String value = trimmed.substring(equals + 1).strip();
				if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
						|| value.startsWith("'") && value.endsWith("'"))) {
					value = value.substring(1, value.length() - 1);
				}
				fileValues.putIfAbsent(trimmed.substring(0, equals).strip(), value);
			}
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot read " + file, e);
		}
	}
}
