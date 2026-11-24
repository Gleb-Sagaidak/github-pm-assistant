package cz.cvut.pmassistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;

/**
 * The architecture rules must be able to fail: a rule that matches nothing passes forever. Each rule is checked
 * against classes in {@code archfixture} that break it on purpose. This is a separate class because the ArchUnit
 * engine only runs the {@code @ArchTest} rules of a class annotated {@code @AnalyzeClasses}.
 */
class ArchitectureRulesCanFailTest {

	private static JavaClasses fixture() {
		return new ClassFileImporter().importPackages("cz.cvut.pmassistant.archfixture");
	}

	@Test
	void theWriterRuleCatchesAClassThatUsesTheWriter() {
		EvaluationResult result = ArchitectureTest.onlyTheExecutorWritesToGitHub().evaluate(fixture());

		assertThat(result.hasViolation()).isTrue();
		assertThat(result.getFailureReport().getDetails()).anyMatch(line -> line.contains("WritesToGitHub"));
	}

	@Test
	void theAgentRuleCatchesAnAgentClassThatDependsOnAForbiddenPackage() {
		EvaluationResult result = ArchitectureTest.noDependencyOn("java.util.logging..").evaluate(fixture());

		assertThat(result.hasViolation()).isTrue();
		assertThat(result.getFailureReport().getDetails()).anyMatch(line -> line.contains("UsesAForbiddenPackage"));
	}

	@Test
	void theAgentRuleLeavesClassesOutsideAgentPackagesAlone() {
		EvaluationResult result = ArchitectureTest.noDependencyOn("java.util.logging..")
				.evaluate(new ClassFileImporter().importClasses(OutsideAgentPackages.class));

		assertThat(result.hasViolation()).isFalse();
	}

	@Test
	void theProviderListCoversTheCommonSdks() {
		assertThat(ArchitectureTest.LLM_PROVIDER_SDKS).contains("com.anthropic..", "com.openai..",
				"com.google.genai..", "dev.langchain4j..", "org.springframework.ai..");
	}

	/** Uses logging but lives outside any agent package, so the agent rule must not care. */
	static final class OutsideAgentPackages {
		java.util.logging.Logger logger;
	}
}
