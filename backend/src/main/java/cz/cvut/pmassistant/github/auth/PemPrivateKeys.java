package cz.cvut.pmassistant.github.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the RSA private key of the GitHub App from a PEM file.
 * <p>
 * GitHub issues keys as PKCS#1 ({@code BEGIN RSA PRIVATE KEY}), but the JDK only reads PKCS#8
 * ({@code BEGIN PRIVATE KEY}), so PKCS#1 is wrapped into a PKCS#8 envelope. Error messages never include
 * key material.
 */
final class PemPrivateKeys {

	private static final Pattern PEM = Pattern.compile(
			"-----BEGIN ([A-Z ]+)-----(.+?)-----END \\1-----", Pattern.DOTALL);

	/** DER: AlgorithmIdentifier { rsaEncryption, NULL }. */
	private static final byte[] RSA_ALGORITHM_ID = {
			0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
			0x05, 0x00 };

	private PemPrivateKeys() {
	}

	static PrivateKey read(Path file) {
		try {
			return parse(Files.readString(file, StandardCharsets.US_ASCII));
		}
		catch (IOException e) {
			throw new UncheckedIOException("Cannot read GitHub App private key file: " + file, e);
		}
	}

	static PrivateKey parse(String pem) {
		Matcher matcher = PEM.matcher(pem);
		if (!matcher.find()) {
			throw new IllegalArgumentException("Not a PEM-encoded private key");
		}
		String type = matcher.group(1);
		byte[] der = Base64.getMimeDecoder().decode(matcher.group(2));
		byte[] pkcs8 = switch (type) {
			case "PRIVATE KEY" -> der;
			case "RSA PRIVATE KEY" -> wrapPkcs1(der);
			default -> throw new IllegalArgumentException("Unsupported PEM type: " + type
					+ " (expected PRIVATE KEY or RSA PRIVATE KEY)");
		};
		try {
			return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalArgumentException("Not a valid RSA private key", e);
		}
	}

	/** PrivateKeyInfo ::= SEQUENCE { version INTEGER 0, algorithm AlgorithmIdentifier, key OCTET STRING }. */
	private static byte[] wrapPkcs1(byte[] pkcs1) {
		byte[] version = { 0x02, 0x01, 0x00 };
		byte[] key = der(0x04, pkcs1);
		byte[] body = new byte[version.length + RSA_ALGORITHM_ID.length + key.length];
		System.arraycopy(version, 0, body, 0, version.length);
		System.arraycopy(RSA_ALGORITHM_ID, 0, body, version.length, RSA_ALGORITHM_ID.length);
		System.arraycopy(key, 0, body, version.length + RSA_ALGORITHM_ID.length, key.length);
		return der(0x30, body);
	}

	private static byte[] der(int tag, byte[] content) {
		int length = content.length;
		byte[] header;
		if (length < 0x80) {
			header = new byte[] { (byte) tag, (byte) length };
		}
		else if (length < 0x100) {
			header = new byte[] { (byte) tag, (byte) 0x81, (byte) length };
		}
		else if (length < 0x10000) {
			header = new byte[] { (byte) tag, (byte) 0x82, (byte) (length >> 8), (byte) length };
		}
		else {
			header = new byte[] { (byte) tag, (byte) 0x83, (byte) (length >> 16), (byte) (length >> 8),
					(byte) length };
		}
		byte[] result = new byte[header.length + length];
		System.arraycopy(header, 0, result, 0, header.length);
		System.arraycopy(content, 0, result, header.length, length);
		return result;
	}
}
