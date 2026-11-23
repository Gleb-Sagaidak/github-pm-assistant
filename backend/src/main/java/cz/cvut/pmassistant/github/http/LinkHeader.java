package cz.cvut.pmassistant.github.http;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the {@code Link} response header that GitHub uses for pagination (RFC 8288). */
final class LinkHeader {

	/** {@code <https://api.github.com/x?page=2>; rel="next"} */
	private static final Pattern LINK = Pattern.compile("<([^>]*)>\\s*((?:;\\s*[^;,]+)*)");
	private static final Pattern REL = Pattern.compile(";\\s*rel\\s*=\\s*\"?([^\";,]+)\"?", Pattern.CASE_INSENSITIVE);

	private LinkHeader() {
	}

	/**
	 * The URL with {@code rel="next"}.
	 *
	 * @param values every {@code Link} header line of the response; may be null or empty
	 * @return the URL as sent by the server, or null if there is no next page
	 */
	static String next(List<String> values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			Matcher link = LINK.matcher(value);
			while (link.find()) {
				Matcher rel = REL.matcher(link.group(2));
				if (rel.find() && hasRel(rel.group(1), "next")) {
					return link.group(1);
				}
			}
		}
		return null;
	}

	/** A {@code rel} value may list several relation types separated by spaces. */
	private static boolean hasRel(String relValue, String wanted) {
		for (String rel : relValue.trim().split("\\s+")) {
			if (rel.equalsIgnoreCase(wanted)) {
				return true;
			}
		}
		return false;
	}
}
