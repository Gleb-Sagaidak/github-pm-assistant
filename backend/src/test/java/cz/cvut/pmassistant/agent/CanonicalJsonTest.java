package cz.cvut.pmassistant.agent;

import static cz.cvut.pmassistant.agent.AgentTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

	@Test
	void objectKeysAreSortedAndSpacesRemoved() {
		assertThat(CanonicalJson.of(json("{ \"b\": 1, \"a\": 2 }"))).isEqualTo("{\"a\":2,\"b\":1}");
	}

	@Test
	void sortingReachesNestedObjectsButKeepsArrayOrder() {
		assertThat(CanonicalJson.of(json("{\"z\":[{\"y\":1,\"x\":2},3,1],\"a\":{\"d\":null,\"c\":true}}")))
				.isEqualTo("{\"a\":{\"c\":true,\"d\":null},\"z\":[{\"x\":2,\"y\":1},3,1]}");
	}

	@Test
	void sameContentInDifferentKeyOrderGivesTheSameText() {
		assertThat(CanonicalJson.of(json("{\"repo\":\"a\",\"number\":7}")))
				.isEqualTo(CanonicalJson.of(json("{\"number\":7,\"repo\":\"a\"}")));
	}

	@Test
	void differentContentGivesDifferentText() {
		assertThat(CanonicalJson.of(json("{\"number\":7}"))).isNotEqualTo(CanonicalJson.of(json("{\"number\":8}")));
		assertThat(CanonicalJson.of(json("{\"n\":\"7\"}"))).isNotEqualTo(CanonicalJson.of(json("{\"n\":7}")));
		assertThat(CanonicalJson.of(json("[1,2]"))).isNotEqualTo(CanonicalJson.of(json("[2,1]")));
	}

	@Test
	void stringsAreEscapedSoTheyCannotBlurTheStructure() {
		assertThat(CanonicalJson.of(json("{\"a\":\"x\\\",\\\"b\\\":\\\"y\"}"))).isEqualTo("{\"a\":\"x\\\",\\\"b\\\":\\\"y\"}");
		assertThat(CanonicalJson.of(json("{\"a\":\"x\",\"b\":\"y\"}")))
				.isNotEqualTo(CanonicalJson.of(json("{\"a\":\"x\\\",\\\"b\\\":\\\"y\"}")));
	}

	@Test
	void scalarsAndEmptyContainersWork() {
		assertThat(CanonicalJson.of(json("{}"))).isEqualTo("{}");
		assertThat(CanonicalJson.of(json("[]"))).isEqualTo("[]");
		assertThat(CanonicalJson.of(json("null"))).isEqualTo("null");
		assertThat(CanonicalJson.of(json("\"text\""))).isEqualTo("\"text\"");
	}
}
