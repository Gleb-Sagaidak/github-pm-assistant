package cz.cvut.pmassistant;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The structural rules the design depends on: the domain does not know the LLM provider (NFR5), nothing but the
 * executor may write to GitHub (NFR1), and the layers do not reach into each other.
 */
@AnalyzeClasses(packages = "cz.cvut.pmassistant", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	/** SDKs and frameworks of language-model providers. Agents and proposals must not touch any of them. */
	static final String[] LLM_PROVIDER_SDKS = { "com.anthropic..", "com.openai..", "com.google.genai..",
			"com.google.cloud.vertexai..", "com.azure.ai..", "software.amazon.awssdk.services.bedrock..",
			"dev.langchain4j..", "org.springframework.ai..", "io.github.ollama4j.." };

	static ArchRule noDependencyOn(String... packages) {
		return noClasses().that().resideInAnyPackage("..agent..", "..proposal..")
				.should().dependOnClassesThat().resideInAnyPackage(packages)
				.allowEmptyShould(true);
	}

	/** Only the executor (stage 4) and the writer's own package may use the write interface. */
	static ArchRule onlyTheExecutorWritesToGitHub() {
		return noClasses()
				.that().resideOutsideOfPackage("cz.cvut.pmassistant.github..")
				.and().haveSimpleNameNotEndingWith("ActionExecutor")
				.should().dependOnClassesThat().haveFullyQualifiedName("cz.cvut.pmassistant.github.GitHubWriter")
				.allowEmptyShould(true);
	}

	@ArchTest
	static final ArchRule agentsDoNotKnowAnyLlmProviderSdk = noDependencyOn(LLM_PROVIDER_SDKS);

	@ArchTest
	static final ArchRule theLlmModelAndPortDoNotKnowAnyLlmProviderSdk = noClasses()
			.that().resideInAPackage("cz.cvut.pmassistant.llm")
			.or().resideInAPackage("cz.cvut.pmassistant.llm.model..")
			.or().resideInAPackage("cz.cvut.pmassistant.llm.fake..")
			.should().dependOnClassesThat().resideInAnyPackage(LLM_PROVIDER_SDKS)
			.allowEmptyShould(true);

	@ArchTest
	static final ArchRule agentsOnlyKnowTheLlmPortNotItsAdapters = noClasses()
			.that().resideInAnyPackage("..agent..", "..proposal..")
			.should().dependOnClassesThat().resideInAnyPackage("cz.cvut.pmassistant.llm.gateway..")
			.allowEmptyShould(true);

	@ArchTest
	static final ArchRule theLlmPackageDoesNotKnowTheRestOfTheApplication = noClasses()
			.that().resideInAPackage("cz.cvut.pmassistant.llm..")
			.should().dependOnClassesThat().resideInAnyPackage("cz.cvut.pmassistant.agent..",
					"cz.cvut.pmassistant.github..", "cz.cvut.pmassistant.proposal..", "cz.cvut.pmassistant.tenant..");

	@ArchTest
	static final ArchRule theLlmModelIsPlainJava = noClasses()
			.that().resideInAPackage("cz.cvut.pmassistant.llm.model..")
			.should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..",
					"org.hibernate..");

	@ArchTest
	static final ArchRule theAgentLoopDoesNotKnowTheDatabase = noClasses()
			.that().resideInAPackage("cz.cvut.pmassistant.agent")
			.should().dependOnClassesThat().resideInAnyPackage("cz.cvut.pmassistant.agent.persistence..",
					"jakarta.persistence..", "org.springframework.data..", "org.hibernate..");

	@ArchTest
	static final ArchRule githubClientDoesNotKnowAgentsOrLlm = noClasses()
			.that().resideInAPackage("cz.cvut.pmassistant.github..")
			.should().dependOnClassesThat().resideInAnyPackage("cz.cvut.pmassistant.agent..",
					"cz.cvut.pmassistant.llm..", "cz.cvut.pmassistant.proposal..");

	@ArchTest
	static final ArchRule githubClientDoesNotKnowTenants = noClasses()
			.that().resideInAPackage("cz.cvut.pmassistant.github..")
			.should().dependOnClassesThat().resideInAPackage("cz.cvut.pmassistant.tenant..");

	@ArchTest
	static final ArchRule onlyTheExecutorMayWriteToGitHub = onlyTheExecutorWritesToGitHub();
}
