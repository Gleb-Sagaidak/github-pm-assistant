package cz.cvut.pmassistant.agent;

import java.util.List;
import java.util.Locale;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaException;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.databind.JsonNode;

/**
 * Checks tool arguments against a JSON Schema (draft 2020-12). This is the server-side line of defence for the
 * final answer (NFR7): the provider may guarantee the shape too, but the system never relies on that alone.
 */
final class ArgumentValidator {

	private final Schema schema;

	/**
	 * @throws IllegalArgumentException if the schema itself cannot be used
	 */
	ArgumentValidator(JsonNode jsonSchema) {
		try {
			// English messages whatever the server's locale is: they are read by the model, and by the logs.
			SchemaRegistryConfig config = SchemaRegistryConfig.builder().locale(Locale.ENGLISH).build();
			this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
					builder -> builder.schemaRegistryConfig(config)).getSchema(jsonSchema);
			// Compile now so a broken schema fails when the agent is defined, not in the middle of a run.
			this.schema.initializeValidators();
		}
		catch (SchemaException e) {
			throw new IllegalArgumentException("Invalid JSON Schema: " + e.getMessage(), e);
		}
	}

	/** The problems found, one readable line each; empty if the arguments are valid. */
	List<String> validate(JsonNode arguments) {
		return schema.validate(arguments).stream().map(ArgumentValidator::describe).toList();
	}

	/** The message names the field (a JSON path such as {@code $.category}) so the model knows what to fix. */
	private static String describe(Error error) {
		String message = error.getMessage();
		String location = String.valueOf(error.getInstanceLocation());
		return message.contains(location) ? message : location + ": " + message;
	}
}
