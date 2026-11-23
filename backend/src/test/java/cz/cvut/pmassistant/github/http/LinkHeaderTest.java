package cz.cvut.pmassistant.github.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class LinkHeaderTest {

	@Test
	void findsNextAmongGitHubRelations() {
		String header = "<https://api.github.com/repositories/1/issues?per_page=100&page=2>; rel=\"next\", "
				+ "<https://api.github.com/repositories/1/issues?per_page=100&page=5>; rel=\"last\"";

		assertThat(LinkHeader.next(List.of(header)))
				.isEqualTo("https://api.github.com/repositories/1/issues?per_page=100&page=2");
	}

	@Test
	void findsNextWhenItIsNotFirst() {
		String header = "<https://api.github.com/x?page=1>; rel=\"prev\", <https://api.github.com/x?page=3>; rel=\"next\", "
				+ "<https://api.github.com/x?page=9>; rel=\"last\", <https://api.github.com/x?page=1>; rel=\"first\"";

		assertThat(LinkHeader.next(List.of(header))).isEqualTo("https://api.github.com/x?page=3");
	}

	@Test
	void returnsNullOnTheLastPage() {
		String header = "<https://api.github.com/x?page=1>; rel=\"prev\", <https://api.github.com/x?page=1>; rel=\"first\"";

		assertThat(LinkHeader.next(List.of(header))).isNull();
	}

	@Test
	void returnsNullWithoutTheHeader() {
		assertThat(LinkHeader.next(null)).isNull();
		assertThat(LinkHeader.next(List.of())).isNull();
	}

	@Test
	void readsRelWithoutQuotesAndAnyCase() {
		assertThat(LinkHeader.next(List.of("<https://a/x?page=2>; REL=next"))).isEqualTo("https://a/x?page=2");
	}

	@Test
	void acceptsSeveralRelationTypesInOneLink() {
		assertThat(LinkHeader.next(List.of("<https://a/x?page=2>; rel=\"last next\""))).isEqualTo("https://a/x?page=2");
	}

	@Test
	void searchesAllHeaderLines() {
		List<String> lines = List.of("<https://a/x?page=1>; rel=\"prev\"", "<https://a/x?page=3>; rel=\"next\"");

		assertThat(LinkHeader.next(lines)).isEqualTo("https://a/x?page=3");
	}

	@Test
	void ignoresGarbage() {
		assertThat(LinkHeader.next(List.of("not a link header"))).isNull();
		assertThat(LinkHeader.next(List.of("<>; rel=\"prev\""))).isNull();
	}
}
