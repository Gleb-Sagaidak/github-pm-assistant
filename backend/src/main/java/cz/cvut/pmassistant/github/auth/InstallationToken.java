package cz.cvut.pmassistant.github.auth;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** GitHub's answer to {@code POST /app/installations/{id}/access_tokens}; the token is valid for one hour. */
@JsonIgnoreProperties(ignoreUnknown = true)
record InstallationToken(String token, @JsonProperty("expires_at") Instant expiresAt) {

	/** Never prints the secret, so an accidental log line or exception message cannot leak it. */
	@Override
	public String toString() {
		return "InstallationToken[expiresAt=" + expiresAt + "]";
	}
}
