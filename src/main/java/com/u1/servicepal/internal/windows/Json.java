package com.u1.servicepal.internal.windows;

import com.u1.servicepal.DefinitionIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader/writer confined to the Windows sidecar. It handles exactly the value
 * shapes the sidecar uses — objects, arrays, and strings — with scalars encoded as strings (no
 * numbers/booleans), so the parser stays small and the round-trip is entirely under our control.
 * This is deliberately <em>not</em> a general-purpose JSON library (that would be a dependency);
 * it is the Windows counterpart to {@code dd-plist} on macOS, confined behind one class.
 *
 * <p>Parsed objects are {@link LinkedHashMap}{@code <String,Object>}; values are {@link String},
 * {@link List}{@code <Object>}, or nested maps. Writing accepts the same shapes and omits
 * {@code null} values.
 */
final class Json {

	private Json() {
	}

	// ---- writing ----

	static String write(final Map<String, Object> object) {
		final StringBuilder sb = new StringBuilder();
		writeObject(sb, object, 0);
		sb.append('\n');
		return sb.toString();
	}

	private static void writeValue(final StringBuilder sb, final Object value, final int depth) {
		if (value instanceof Map) {
			@SuppressWarnings("unchecked")
			final Map<String, Object> map = (Map<String, Object>) value;
			writeObject(sb, map, depth);
		} else if (value instanceof List) {
			writeArray(sb, (List<?>) value, depth);
		} else {
			writeString(sb, value.toString());
		}
	}

	private static void writeObject(final StringBuilder sb, final Map<String, Object> map,
			final int depth) {
		sb.append('{');
		final String indent = "  ".repeat(depth + 1);
		boolean first = true;
		for (final Map.Entry<String, Object> e : map.entrySet()) {
			if (e.getValue() == null) {
				continue;   // omit absent fields rather than writing null
			}
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('\n').append(indent);
			writeString(sb, e.getKey());
			sb.append(": ");
			writeValue(sb, e.getValue(), depth + 1);
		}
		sb.append('\n').append("  ".repeat(depth)).append('}');
	}

	private static void writeArray(final StringBuilder sb, final List<?> list, final int depth) {
		sb.append('[');
		boolean first = true;
		for (final Object item : list) {
			if (!first) {
				sb.append(", ");
			}
			first = false;
			writeValue(sb, item, depth);
		}
		sb.append(']');
	}

	private static void writeString(final StringBuilder sb, final String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			final char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
	}

	// ---- reading ----

	/** Parse a JSON object. Throws {@link DefinitionIOException} on malformed input. */
	static Map<String, Object> parseObject(final String text) {
		final Parser parser = new Parser(text);
		parser.skipWhitespace();
		final Object value = parser.parseValue();
		parser.skipWhitespace();
		if (!parser.atEnd()) {
			throw new DefinitionIOException("trailing content after JSON value", null);
		}
		if (!(value instanceof Map)) {
			throw new DefinitionIOException("expected a JSON object at the top level", null);
		}
		@SuppressWarnings("unchecked")
		final Map<String, Object> object = (Map<String, Object>) value;
		return object;
	}

	private static final class Parser {

		private final String s;
		private int pos;

		Parser(final String s) {
			this.s = s;
		}

		boolean atEnd() {
			return pos >= s.length();
		}

		void skipWhitespace() {
			while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
				pos++;
			}
		}

		Object parseValue() {
			skipWhitespace();
			if (atEnd()) {
				throw new DefinitionIOException("unexpected end of JSON", null);
			}
			final char c = s.charAt(pos);
			return switch (c) {
				case '{' -> parseObjectValue();
				case '[' -> parseArrayValue();
				case '"' -> parseString();
				case 'n' -> parseNull();
				default -> throw new DefinitionIOException(
						"unexpected character '" + c + "' at position " + pos, null);
			};
		}

		private Map<String, Object> parseObjectValue() {
			final Map<String, Object> map = new LinkedHashMap<>();
			expect('{');
			skipWhitespace();
			if (peek() == '}') {
				pos++;
				return map;
			}
			while (true) {
				skipWhitespace();
				final String key = parseString();
				skipWhitespace();
				expect(':');
				map.put(key, parseValue());
				skipWhitespace();
				final char c = next();
				if (c == '}') {
					return map;
				}
				if (c != ',') {
					throw new DefinitionIOException("expected ',' or '}' in object", null);
				}
			}
		}

		private List<Object> parseArrayValue() {
			final List<Object> list = new ArrayList<>();
			expect('[');
			skipWhitespace();
			if (peek() == ']') {
				pos++;
				return list;
			}
			while (true) {
				list.add(parseValue());
				skipWhitespace();
				final char c = next();
				if (c == ']') {
					return list;
				}
				if (c != ',') {
					throw new DefinitionIOException("expected ',' or ']' in array", null);
				}
			}
		}

		private String parseString() {
			expect('"');
			final StringBuilder sb = new StringBuilder();
			while (true) {
				if (atEnd()) {
					throw new DefinitionIOException("unterminated string", null);
				}
				final char c = s.charAt(pos++);
				if (c == '"') {
					return sb.toString();
				}
				if (c == '\\') {
					sb.append(parseEscape());
				} else {
					sb.append(c);
				}
			}
		}

		private char parseEscape() {
			if (atEnd()) {
				throw new DefinitionIOException("unterminated escape", null);
			}
			final char c = s.charAt(pos++);
			return switch (c) {
				case '"' -> '"';
				case '\\' -> '\\';
				case '/' -> '/';
				case 'n' -> '\n';
				case 'r' -> '\r';
				case 't' -> '\t';
				case 'b' -> '\b';
				case 'f' -> '\f';
				case 'u' -> parseUnicodeEscape();
				default -> throw new DefinitionIOException("invalid escape '\\" + c + "'", null);
			};
		}

		private char parseUnicodeEscape() {
			if (pos + 4 > s.length()) {
				throw new DefinitionIOException("truncated \\u escape", null);
			}
			final String hex = s.substring(pos, pos + 4);
			pos += 4;
			try {
				return (char) Integer.parseInt(hex, 16);
			} catch (final NumberFormatException e) {
				throw new DefinitionIOException("invalid \\u escape '" + hex + "'", e);
			}
		}

		private Object parseNull() {
			if (s.regionMatches(pos, "null", 0, 4)) {
				pos += 4;
				return null;
			}
			throw new DefinitionIOException("invalid literal at position " + pos, null);
		}

		private char peek() {
			if (atEnd()) {
				throw new DefinitionIOException("unexpected end of JSON", null);
			}
			return s.charAt(pos);
		}

		private char next() {
			if (atEnd()) {
				throw new DefinitionIOException("unexpected end of JSON", null);
			}
			return s.charAt(pos++);
		}

		private void expect(final char c) {
			final char actual = next();
			if (actual != c) {
				throw new DefinitionIOException(
						"expected '" + c + "' but found '" + actual + "' at position " + (pos - 1),
						null);
			}
		}
	}
}
