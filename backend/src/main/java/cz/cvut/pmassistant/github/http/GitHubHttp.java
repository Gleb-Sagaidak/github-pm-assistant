package cz.cvut.pmassistant.github.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single place that talks HTTP to GitHub: adds the standard headers and a fresh bearer token to every
 * request, retries temporary failures through {@link RetryExecutor} and turns every other failure into a
 * {@link GitHubApiException}. It does not follow redirects: the token must never be sent to a host other than
 * the configured one.
 */
public class GitHubHttp {

	static final String USER_AGENT = "github-pm-assistant";
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

	/** Default cap on pages per listing: 100 pages of 100 items. */
	static final int DEFAULT_MAX_PAGES = 100;
	static final int PER_PAGE = 100;

	private static final int MAX_MESSAGE_LENGTH = 300;

	private final String baseUrl;
	private final URI baseUri;
	private final int maxPages;
	private final BearerTokenSource tokens;
	private final Clock clock;
	private final RetryExecutor retry;
	private final RestClient client;
	private final JsonMapper errorMapper = JsonMapper.builder().build();

	/**
	 * @param baseUrl    {@code https://api.github.com}; WireMock's URL in tests
	 * @param apiVersion value of {@code X-GitHub-Api-Version}, e.g. {@code 2026-03-10}
	 */
	public GitHubHttp(String baseUrl, String apiVersion, BearerTokenSource tokens, Clock clock,
			RetryExecutor retry) {
		this(baseUrl, apiVersion, tokens, clock, retry, DEFAULT_MAX_PAGES);
	}

	/**
	 * @param maxPages most pages {@code getAll} may fetch for one listing before it fails
	 */
	public GitHubHttp(String baseUrl, String apiVersion, BearerTokenSource tokens, Clock clock,
			RetryExecutor retry, int maxPages) {
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.baseUri = URI.create(this.baseUrl);
		this.maxPages = maxPages;
		this.tokens = tokens;
		this.clock = clock;
		this.retry = retry;
		HttpClient httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(READ_TIMEOUT);
		this.client = RestClient.builder()
				.requestFactory(requestFactory)
				.defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
				.defaultHeader("X-GitHub-Api-Version", apiVersion)
				.defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
				.build();
	}

	public <T> T get(String path, Class<T> type) {
		return get(path, ParameterizedTypeReference.<T>forType(type));
	}

	public <T> T get(String path, ParameterizedTypeReference<T> type) {
		return exchange(HttpMethod.GET, path, null, type).body();
	}

	public <T> T post(String path, Object body, Class<T> type) {
		return exchange(HttpMethod.POST, path, body, ParameterizedTypeReference.<T>forType(type)).body();
	}

	/** Fetches every page of a list endpoint ({@code per_page=100}) and returns the items in order. */
	public <T> List<T> getAll(String path, Class<T> elementType) {
		ParameterizedTypeReference<List<T>> listType = ParameterizedTypeReference
				.forType(ResolvableType.forClassWithGenerics(List.class, elementType).getType());
		return getAll(path, listType, Function.identity());
	}

	/**
	 * Fetches every page by following {@code Link: rel="next"}, for endpoints whose pages are wrapped in an
	 * object (such as {@code {"total_count": 3, "installations": [...]}}).
	 *
	 * @param items extracts the list of items from one page
	 * @throws PaginationException if there are more than {@code maxPages} pages, the links loop, or a link
	 *                             points to another host; a partial list is never returned
	 */
	public <P, T> List<T> getAll(String path, ParameterizedTypeReference<P> pageType, Function<P, List<T>> items) {
		List<T> result = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		String next = withPerPage(path);
		for (int page = 1; next != null; page++) {
			if (page > maxPages) {
				throw new PaginationException("More than " + maxPages + " pages for " + path);
			}
			if (!seen.add(next)) {
				throw new PaginationException("Pagination loop at " + next);
			}
			GitHubResponse<P> response = exchange(HttpMethod.GET, next, null, pageType);
			if (response.body() != null) {
				result.addAll(items.apply(response.body()));
			}
			next = nextPath(response.headers());
		}
		return result;
	}

	private static String withPerPage(String path) {
		if (path.contains("per_page=")) {
			return path;
		}
		return path + (path.contains("?") ? "&" : "?") + "per_page=" + PER_PAGE;
	}

	/** The next page as a path relative to the base URL; null on the last page. */
	private String nextPath(HttpHeaders headers) {
		String link = LinkHeader.next(headers.get(HttpHeaders.LINK));
		if (link == null) {
			return null;
		}
		URI uri;
		try {
			uri = URI.create(link);
		}
		catch (IllegalArgumentException e) {
			throw new PaginationException("Malformed next link: " + link, e);
		}
		if (uri.isAbsolute() && !sameOrigin(uri, baseUri)) {
			throw new PaginationException("Next link leads to another host, not followed: " + link);
		}
		String path = uri.getRawPath() == null ? "" : uri.getRawPath();
		String basePath = baseUri.getRawPath() == null ? "" : baseUri.getRawPath();
		if (uri.isAbsolute() && !basePath.isEmpty() && path.startsWith(basePath)) {
			path = path.substring(basePath.length());
		}
		if (!path.startsWith("/")) {
			throw new PaginationException("Unexpected next link: " + link);
		}
		return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
	}

	private static boolean sameOrigin(URI a, URI b) {
		return a.getScheme().equalsIgnoreCase(b.getScheme())
				&& a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
				&& effectivePort(a) == effectivePort(b);
	}

	private static int effectivePort(URI uri) {
		if (uri.getPort() != -1) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	/**
	 * Performs a request, repeating it while the failure is temporary (see {@link RetryExecutor}). Every attempt
	 * asks the token source again.
	 *
	 * @param path path and query relative to the base URL, starting with {@code /}; already URL-encoded
	 * @param body request body to serialize as JSON, or {@code null}
	 * @throws GitHubApiException for every non-2xx answer and for network failures, after retries
	 */
	public <T> GitHubResponse<T> exchange(HttpMethod method, String path, Object body,
			ParameterizedTypeReference<T> type) {
		if (!path.startsWith("/")) {
			throw new IllegalArgumentException("GitHub path must be relative and start with '/': " + path);
		}
		return retry.execute(() -> attempt(method, path, body, type));
	}

	private <T> GitHubResponse<T> attempt(HttpMethod method, String path, Object body,
			ParameterizedTypeReference<T> type) {
		String methodName = method.name();
		RestClient.RequestBodySpec request = client.method(method)
				.uri(URI.create(baseUrl + path))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).body(body);
		}
		try {
			return request.exchange((req, res) -> {
				if (res.getStatusCode().is2xxSuccessful()) {
					return new GitHubResponse<>(res.bodyTo(type), res.getHeaders());
				}
				throw toException(methodName, path, res);
			}, true);
		}
		catch (ResourceAccessException e) {
			throw new GitHubApiException.Transient("network error: " + e.getMessage(), methodName, path, 0, null, e);
		}
	}

	private GitHubApiException toException(String method, String path, ClientHttpResponse res) throws IOException {
		int status = res.getStatusCode().value();
		HttpHeaders headers = res.getHeaders();
		String requestId = headers.getFirst("x-github-request-id");
		String body = StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8);
		String message = errorMessage(body);

		if (status == 401) {
			return new GitHubApiException.Unauthorized(message, method, path, requestId);
		}
		if (status == 429 || (status == 403 && looksRateLimited(headers, message))) {
			return new GitHubApiException.RateLimited(message, method, path, status, requestId,
					retryHint(headers));
		}
		if (status == 403) {
			return new GitHubApiException.Forbidden(message, method, path, requestId);
		}
		if (status == 404) {
			return new GitHubApiException.NotFound(message, method, path, requestId);
		}
		if (status >= 500) {
			return new GitHubApiException.Transient(message, method, path, status, requestId, null);
		}
		if (status >= 300 && status < 400) {
			message = "unexpected redirect to " + headers.getFirst(HttpHeaders.LOCATION);
		}
		return new GitHubApiException(message, method, path, status, requestId, null);
	}

	private static boolean looksRateLimited(HttpHeaders headers, String message) {
		return "0".equals(headers.getFirst("x-ratelimit-remaining"))
				|| headers.getFirst(HttpHeaders.RETRY_AFTER) != null
				|| (message != null && message.toLowerCase(Locale.ROOT).contains("rate limit"));
	}

	/** How long GitHub asks us to wait: {@code retry-after}, else until {@code x-ratelimit-reset}; may be null. */
	private Duration retryHint(HttpHeaders headers) {
		Long retryAfter = parseLong(headers.getFirst(HttpHeaders.RETRY_AFTER));
		if (retryAfter != null) {
			return Duration.ofSeconds(Math.max(retryAfter, 0));
		}
		Long reset = parseLong(headers.getFirst("x-ratelimit-reset"));
		if (reset != null) {
			Duration untilReset = Duration.between(clock.instant(), Instant.ofEpochSecond(reset));
			return untilReset.isNegative() ? Duration.ZERO : untilReset;
		}
		return null;
	}

	private static Long parseLong(String value) {
		if (value == null) {
			return null;
		}
		try {
			return Long.parseLong(value.trim());
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	/** The {@code message} of GitHub's JSON error body, or a shortened raw body if it is not JSON. */
	private String errorMessage(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			JsonNode message = errorMapper.readTree(body).get("message");
			if (message != null && message.isString()) {
				return truncate(message.asString());
			}
		}
		catch (RuntimeException ignored) {
			// not JSON: fall through to the raw body
		}
		return truncate(body);
	}

	private static String truncate(String text) {
		return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH) + "...";
	}
}
