package cz.cvut.pmassistant.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Writes JSON in one fixed form (object keys sorted, no spaces), so two calls with the same arguments written in
 * a different key order are recognised as the same call.
 */
final class CanonicalJson {

	private CanonicalJson() {
	}

	static String of(JsonNode node) {
		StringBuilder out = new StringBuilder();
		write(node, out);
		return out.toString();
	}

	private static void write(JsonNode node, StringBuilder out) {
		if (node.isObject()) {
			List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
			entries.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));
			out.append('{');
			for (int i = 0; i < entries.size(); i++) {
				if (i > 0) {
					out.append(',');
				}
				out.append(quote(entries.get(i).getKey())).append(':');
				write(entries.get(i).getValue(), out);
			}
			out.append('}');
		}
		else if (node.isArray()) {
			out.append('[');
			boolean first = true;
			for (JsonNode element : node) {
				if (!first) {
					out.append(',');
				}
				first = false;
				write(element, out);
			}
			out.append(']');
		}
		else {
			// Scalars already have a single canonical rendering (strings are quoted and escaped).
			out.append(node.toString());
		}
	}

	private static String quote(String key) {
		return tools.jackson.databind.node.StringNode.valueOf(key).toString();
	}
}
