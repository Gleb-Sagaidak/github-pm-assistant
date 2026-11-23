package cz.cvut.pmassistant.github.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import cz.cvut.pmassistant.github.http.BearerTokenSource;
import cz.cvut.pmassistant.github.http.GitHubHttp;

/**
 * Hands out installation access tokens and caches them per installation (every team lead has an installation
 * of their own). A cached token is replaced {@value #REFRESH_MARGIN_MINUTES} minutes before it expires.
 * <p>
 * Concurrent callers that need a fresh token for the same installation share one request: the first one
 * fetches, the others wait on a per-installation lock and then find the new token in the cache. Different
 * installations never wait for each other. A failed request caches nothing.
 * <p>
 * Together with {@link AppJwtFactory} this is one of the few places that handle credentials; tokens are
 * never logged.
 */
public class InstallationTokenProvider {

	static final int REFRESH_MARGIN_MINUTES = 5;
	private static final Duration REFRESH_MARGIN = Duration.ofMinutes(REFRESH_MARGIN_MINUTES);

	private record Key(long installationId, TokenScope scope) {
	}

	private final GitHubHttp appHttp;
	private final Clock clock;
	private final Map<Key, InstallationToken> cache = new ConcurrentHashMap<>();
	private final Map<Key, Lock> locks = new ConcurrentHashMap<>();

	/**
	 * @param appHttp a client that authenticates as the App itself (its token source is the App JWT)
	 */
	public InstallationTokenProvider(GitHubHttp appHttp, Clock clock) {
		this.appHttp = appHttp;
		this.clock = clock;
	}

	/** A token with everything the installation was granted. */
	public String token(long installationId) {
		return token(installationId, TokenScope.FULL);
	}

	/** A token narrowed to {@code scope}; tokens of different scopes are cached separately. */
	public String token(long installationId, TokenScope scope) {
		Key key = new Key(installationId, scope);
		InstallationToken cached = cache.get(key);
		if (isUsable(cached)) {
			return cached.token();
		}
		Lock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
		lock.lock();
		try {
			cached = cache.get(key);
			if (isUsable(cached)) {
				return cached.token();
			}
			InstallationToken fresh = fetch(key);
			cache.put(key, fresh);
			return fresh.token();
		}
		finally {
			lock.unlock();
		}
	}

	/** For {@link cz.cvut.pmassistant.github.http.GitHubHttp}: asks for the token on every request. */
	public BearerTokenSource tokenSource(long installationId, TokenScope scope) {
		return () -> token(installationId, scope);
	}

	/** Forgets the cached tokens of an installation, e.g. after GitHub answered 401 or it was removed. */
	public void invalidate(long installationId) {
		cache.keySet().removeIf(key -> key.installationId() == installationId);
	}

	private boolean isUsable(InstallationToken token) {
		if (token == null || token.expiresAt() == null) {
			return false;
		}
		Instant refreshAt = token.expiresAt().minus(REFRESH_MARGIN);
		return clock.instant().isBefore(refreshAt);
	}

	private InstallationToken fetch(Key key) {
		Map<String, Object> body = null;
		if (!key.scope().isFull()) {
			body = new LinkedHashMap<>();
			if (!key.scope().repositoryIds().isEmpty()) {
				body.put("repository_ids", key.scope().repositoryIds());
			}
			if (!key.scope().permissions().isEmpty()) {
				body.put("permissions", key.scope().permissions());
			}
		}
		InstallationToken token = appHttp.post("/app/installations/" + key.installationId() + "/access_tokens",
				body, InstallationToken.class);
		if (token == null || token.token() == null || token.expiresAt() == null) {
			throw new IllegalStateException("GitHub returned an incomplete access token response for installation "
					+ key.installationId());
		}
		return token;
	}
}
