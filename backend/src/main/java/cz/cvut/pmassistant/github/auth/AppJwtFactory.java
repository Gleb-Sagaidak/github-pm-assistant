package cz.cvut.pmassistant.github.auth;

import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Creates the short-lived JWT with which the GitHub App authenticates as itself (RS256).
 * <p>
 * {@code iat} is set 60 s in the past to tolerate clock drift between us and GitHub, and {@code exp} 9 minutes
 * ahead (GitHub accepts at most 10). This is one of the few classes allowed to touch the private key.
 */
public class AppJwtFactory {

	static final Duration BACKDATE = Duration.ofSeconds(60);
	static final Duration LIFETIME = Duration.ofMinutes(9);

	private final String issuer;
	private final RSASSASigner signer;
	private final Clock clock;

	/**
	 * @param issuer Client ID (preferred by GitHub) or App ID of the GitHub App
	 */
	public AppJwtFactory(String issuer, PrivateKey privateKey, Clock clock) {
		this.issuer = issuer;
		this.signer = new RSASSASigner(privateKey);
		this.clock = clock;
	}

	/** Loads the key from a PEM file (PKCS#1 as issued by GitHub, or PKCS#8). */
	public static AppJwtFactory fromPemFile(String issuer, Path privateKeyFile, Clock clock) {
		return new AppJwtFactory(issuer, PemPrivateKeys.read(privateKeyFile), clock);
	}

	/** A freshly signed JWT; cheap to create, so callers do not need to cache it. */
	public String create() {
		Instant now = clock.instant();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer(issuer)
				.issueTime(Date.from(now.minus(BACKDATE)))
				.expirationTime(Date.from(now.plus(LIFETIME)))
				.build();
		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims);
		try {
			jwt.sign(signer);
		}
		catch (JOSEException e) {
			throw new IllegalStateException("Cannot sign GitHub App JWT", e);
		}
		return jwt.serialize();
	}
}
