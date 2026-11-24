package cz.cvut.pmassistant.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RunScopedCacheTest {

	@Test
	void loadsOnceAndRemembers() {
		RunScopedCache cache = new RunScopedCache();
		AtomicInteger loads = new AtomicInteger();

		String first = cache.get("k", () -> "v" + loads.incrementAndGet());
		String second = cache.get("k", () -> "v" + loads.incrementAndGet());

		assertThat(first).isEqualTo("v1");
		assertThat(second).isEqualTo("v1");
		assertThat(loads).hasValue(1);
	}

	@Test
	void keysAreComparedByValue() {
		RunScopedCache cache = new RunScopedCache();

		cache.get(java.util.List.of("labels", 1L), () -> "a");

		assertThat(cache.get(java.util.List.of("labels", 1L), () -> "b")).isEqualTo("a");
		assertThat(cache.get(java.util.List.of("labels", 2L), () -> "c")).isEqualTo("c");
		assertThat(cache.size()).isEqualTo(2);
	}

	@Test
	void aLoaderResultOfNullIsNotRemembered() {
		RunScopedCache cache = new RunScopedCache();

		assertThat((String) cache.get("k", () -> null)).isNull();

		assertThat(cache.size()).isZero();
	}
}
