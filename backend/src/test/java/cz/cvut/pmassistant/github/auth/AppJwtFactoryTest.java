package cz.cvut.pmassistant.github.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;

class AppJwtFactoryTest {

	private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private static KeyPair keys;
	private static KeyPair otherKeys;

	@BeforeAll
	static void generateKeys() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		keys = generator.generateKeyPair();
		otherKeys = generator.generateKeyPair();
	}

	@Test
	void signsWithRs256AndSetsIssuerAndTimes() throws Exception {
		String token = new AppJwtFactory("Iv1.abc", keys.getPrivate(), CLOCK).create();

		SignedJWT jwt = SignedJWT.parse(token);
		assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
		assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) keys.getPublic()))).isTrue();
		assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("Iv1.abc");
		assertThat(jwt.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(NOW.minusSeconds(60));
		assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(NOW.plusSeconds(9 * 60));
	}

	@Test
	void expiresWithinGitHubLimitOfTenMinutesFromNow() throws Exception {
		SignedJWT jwt = SignedJWT.parse(new AppJwtFactory("1", keys.getPrivate(), CLOCK).create());

		Instant exp = jwt.getJWTClaimsSet().getExpirationTime().toInstant();
		assertThat(exp).isBefore(NOW.plusSeconds(10 * 60));
	}

	@Test
	void signatureDoesNotVerifyWithAnotherKey() throws Exception {
		SignedJWT jwt = SignedJWT.parse(new AppJwtFactory("1", keys.getPrivate(), CLOCK).create());

		assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) otherKeys.getPublic()))).isFalse();
	}

	@Test
	void usesTheClockOnEveryCall() throws Exception {
		Clock later = Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC);

		SignedJWT jwt = SignedJWT.parse(new AppJwtFactory("1", keys.getPrivate(), later).create());

		assertThat(jwt.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(NOW.plusSeconds(3600 - 60));
	}

	@Test
	void loadsPkcs8PemFromFile(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("key.pem");
		Files.writeString(file, pem("PRIVATE KEY", keys.getPrivate().getEncoded()), StandardCharsets.US_ASCII);

		String token = AppJwtFactory.fromPemFile("1", file, CLOCK).create();

		assertThat(SignedJWT.parse(token).verify(new RSASSAVerifier((RSAPublicKey) keys.getPublic()))).isTrue();
	}

	@Test
	void loadsPkcs1PemAsIssuedByGitHub(@TempDir Path dir) throws Exception {
		// A JDK-generated 2048-bit key is PKCS#8 with a fixed 26-byte header followed by the PKCS#1 structure.
		byte[] pkcs8 = keys.getPrivate().getEncoded();
		byte[] pkcs1 = Arrays.copyOfRange(pkcs8, 26, pkcs8.length);
		Path file = dir.resolve("github.pem");
		Files.writeString(file, pem("RSA PRIVATE KEY", pkcs1), StandardCharsets.US_ASCII);

		PrivateKey loaded = PemPrivateKeys.read(file);

		assertThat(loaded.getEncoded()).isEqualTo(pkcs8);
		String token = AppJwtFactory.fromPemFile("1", file, CLOCK).create();
		assertThat(SignedJWT.parse(token).verify(new RSASSAVerifier((RSAPublicKey) keys.getPublic()))).isTrue();
	}

	@Test
	void rejectsNonPemWithoutLeakingContent() {
		assertThatThrownBy(() -> PemPrivateKeys.parse("super-secret-garbage"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageNotContaining("super-secret-garbage");
	}

	@Test
	void rejectsUnsupportedPemType() {
		String encrypted = pem("ENCRYPTED PRIVATE KEY", new byte[] { 1, 2, 3 });

		assertThatThrownBy(() -> PemPrivateKeys.parse(encrypted))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ENCRYPTED PRIVATE KEY");
	}

	@Test
	void reportsMissingFile(@TempDir Path dir) {
		assertThatThrownBy(() -> PemPrivateKeys.read(dir.resolve("absent.pem")))
				.isInstanceOf(java.io.UncheckedIOException.class);
	}

	private static String pem(String type, byte[] der) {
		String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
		return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
	}
}
