package cz.cvut.pmassistant.agent;

import static cz.cvut.pmassistant.agent.AgentTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ArgumentValidatorTest {

	/** A schema shaped like a triage answer: an enum, an optional list, nothing else allowed. */
	private static final String TRIAGE_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "category": {"type": "string", "enum": ["bug", "feature", "question"]},
			    "labels": {"type": "array", "items": {"type": "string"}, "maxItems": 3},
			    "confidence": {"type": "number", "minimum": 0, "maximum": 1},
			    "reason": {"type": "string", "minLength": 5}
			  },
			  "required": ["category", "reason"],
			  "additionalProperties": false
			}""";

	private final ArgumentValidator validator = new ArgumentValidator(json(TRIAGE_SCHEMA));

	@Test
	void acceptsArgumentsThatMatch() {
		assertThat(validator.validate(json("{\"category\":\"bug\",\"reason\":\"crashes on login\","
				+ "\"labels\":[\"a\",\"b\"],\"confidence\":0.8}"))).isEmpty();
	}

	@Test
	void reportsAValueOutsideTheEnumeration() {
		assertThat(validator.validate(json("{\"category\":\"chore\",\"reason\":\"because\"}")))
				.singleElement().asString().contains("category", "bug");
	}

	@Test
	void messagesAreEnglishWhateverTheDefaultLocaleIs() {
		java.util.Locale original = java.util.Locale.getDefault();
		try {
			java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru-RU"));
			ArgumentValidator inRussianJvm = new ArgumentValidator(json(TRIAGE_SCHEMA));

			assertThat(inRussianJvm.validate(json("{\"category\":\"chore\",\"reason\":\"because\"}")))
					.singleElement().asString().matches("\\A\\p{ASCII}*\\z").contains("category");
		}
		finally {
			java.util.Locale.setDefault(original);
		}
	}

	@Test
	void reportsAMissingRequiredProperty() {
		assertThat(validator.validate(json("{\"category\":\"bug\"}")))
				.singleElement().asString().contains("reason");
	}

	@Test
	void reportsAPropertyTheSchemaDoesNotAllow() {
		assertThat(validator.validate(json("{\"category\":\"bug\",\"reason\":\"because\",\"severity\":9}")))
				.singleElement().asString().contains("severity");
	}

	@Test
	void reportsAWrongType() {
		assertThat(validator.validate(json("{\"category\":\"bug\",\"reason\":\"because\",\"confidence\":\"high\"}")))
				.isNotEmpty();
	}

	@Test
	void reportsRangeLengthAndSizeViolations() {
		assertThat(validator.validate(json("{\"category\":\"bug\",\"reason\":\"abc\"}"))).isNotEmpty();
		assertThat(validator.validate(json("{\"category\":\"bug\",\"reason\":\"because\",\"confidence\":1.5}")))
				.isNotEmpty();
		assertThat(validator.validate(json("{\"category\":\"bug\",\"reason\":\"because\","
				+ "\"labels\":[\"a\",\"b\",\"c\",\"d\"]}"))).isNotEmpty();
	}

	@Test
	void reportsEveryProblemNotJustTheFirst() {
		assertThat(validator.validate(json("{\"category\":\"chore\",\"extra\":1}"))).hasSizeGreaterThanOrEqualTo(3);
	}

	@Test
	void rejectsArgumentsThatAreNotAnObject() {
		assertThat(validator.validate(json("[1,2,3]"))).isNotEmpty();
		assertThat(validator.validate(json("\"text\""))).isNotEmpty();
	}

	@Test
	void understandsReferencesAndDefinitions() {
		ArgumentValidator withRefs = new ArgumentValidator(json("""
				{
				  "type": "object",
				  "$defs": {"label": {"type": "string", "minLength": 1}},
				  "properties": {"labels": {"type": "array", "items": {"$ref": "#/$defs/label"}}},
				  "required": ["labels"]
				}"""));

		assertThat(withRefs.validate(json("{\"labels\":[\"bug\"]}"))).isEmpty();
		assertThat(withRefs.validate(json("{\"labels\":[\"\"]}"))).isNotEmpty();
	}

	@Test
	void aSchemaThatCannotBeUsedFailsWhenTheValidatorIsCreated() {
		assertThatThrownBy(() -> new ArgumentValidator(json("{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/nope\"}}}")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Invalid JSON Schema");
	}
}
