package cz.cvut.pmassistant.github;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Remembers GitHub answers for the duration of <em>one agent run</em> and is then thrown away. Tools ask for
 * the same labels, collaborators or issue list many times in a run; a global cache would serve stale data,
 * while this one starts empty so every run begins with fresh data, and the snapshot a proposal was based on is
 * exactly what the run saw.
 * <p>
 * A loader must not use the same cache itself, and a loader that throws leaves nothing behind.
 */
public final class RunScopedCache {

	private final ConcurrentHashMap<Object, Object> entries = new ConcurrentHashMap<>();

	/** The remembered value for {@code key}, loading and remembering it on first use. */
	@SuppressWarnings("unchecked")
	public <T> T get(Object key, Supplier<T> loader) {
		return (T) entries.computeIfAbsent(key, k -> loader.get());
	}

	public int size() {
		return entries.size();
	}
}
