package com.u1.servicepal.internal.windows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny, dependency-free JSON reader/writer, confined to the Windows backend for the service
 * sidecar file (we have no JSON library and deliberately avoid Jackson). Supports exactly what
 * the sidecar needs: objects, arrays, strings, booleans and {@code null}. Numbers are read as
 * strings. Not a general-purpose JSON library — it only round-trips its own output.
 */
final class Json {

	private Json() {
	}

	// ---- writing ----

	static String write(final Object value) {
		final StringBuilder sb = new StringBuilder();
		writeValue(sb, value);
		return sb.toString();
	}

	private static void writeValue(final StringBuilder sb, final Object value) {
		if (value == null) {
			sb.append("null");
		} else if (value instanceof String s) {
			writeString(sb, s);
		} else if (value instanceof Boolean b) {
			sb.append(b.booleanValue() ? "true" : "false");
		} else if (value instanceof Map<?, ?> map) {
			writeObject(sb, map);
		} else if (value instanceof List<?> list) {
			writeArray(sb, list);
		} else {
			throw new IllegalArgumentException("unsupported JSON value: " + value.getClass());
		}
	}

	private static void writeObject(final StringBuilder sb, final Map<?, ?> map) {
		sb.append('{');
		boolean first = true;
		for (final Map.Entry<?, ?> e : map.entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			writeString(sb, String.valueOf(e.getKey()));
			sb.append(':');
			writeValue(sb, e.getValue());
		}
		sb.append('}');
	}

	private static void writeArray(final StringBuilder sb, final List<?> list) {
		sb.append('[');
		for (int i = 0; i < list.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			writeValue(sb, list.get(i));
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

	// ---- parsing ----

	static Object parse(final String text) {
		final Parser p = new Parser(text);
		p.skipWhitespace();
		final Object value = p.readValue();
		p.skipWhitespace();
		if (!p.atEnd()) {
			throw new IllegalArgumentException("trailing content at index " + p.pos);
		}
		return value;
	}

	/** Convenience: parse and cast to an object map. */
	@SuppressWarnings("unchecked")
	static Map<String, Object> parseObject(final String text) {
		final Object value = parse(text);
		if (!(value instanceof Map)) {
			throw new IllegalArgumentException("expected a JSON object");
		}
		return (Map<String, Object>) value;
	}

	private static final class Parser {

		private final String src;
		private int pos;

		Parser(final String src) {
			this.src = src;
		}

		boolean atEnd() {
			return pos >= src.length();
		}

		void skipWhitespace() {
			while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
				pos++;
			}
		}

		Object readValue() {
			skipWhitespace();
			if (atEnd()) {
				throw new IllegalArgumentException("unexpected end of input");
			}
			final char c = src.charAt(pos);
			return switch (c) {
				case '{' -> readObject();
				case '[' -> readArray();
				case '"' -> readString();
				case 't', 'f' -> readBoolean();
				case 'n' -> readNull();
				default -> readNumber();
			};
		}

		private Map<String, Object> readObject() {
			expect('{');
			final Map<String, Object> map = new LinkedHashMap<>();
			skipWhitespace();
			if (peek() == '}') {
				pos++;
				return map;
			}
			while (true) {
				skipWhitespace();
				final String key = readString();
				skipWhitespace();
				expect(':');
				map.put(key, readValue());
				skipWhitespace();
				final char c = next();
				if (c == '}') {
					return map;
				}
				if (c != ',') {
					throw new IllegalArgumentException("expected ',' or '}' at index " + (pos - 1));
				}
			}
		}

		private List<Object> readArray() {
			expect('[');
			final List<Object> list = new ArrayList<>();
			skipWhitespace();
			if (peek() == ']') {
				pos++;
				return list;
			}
			while (true) {
				list.add(readValue());
				skipWhitespace();
				final char c = next();
				if (c == ']') {
					return list;
				}
				if (c != ',') {
					throw new IllegalArgumentException("expected ',' or ']' at index " + (pos - 1));
				}
			}
		}

		private String readString() {
			expect('"');
			final StringBuilder sb = new StringBuilder();
			while (true) {
				if (atEnd()) {
					throw new IllegalArgumentException("unterminated string");
				}
				final char c = src.charAt(pos++);
				if (c == '"') {
					return sb.toString();
				}
				if (c == '\\') {
					final char esc = src.charAt(pos++);
					switch (esc) {
						case '"' -> sb.append('"');
						case '\\' -> sb.append('\\');
						case '/' -> sb.append('/');
						case 'n' -> sb.append('\n');
						case 'r' -> sb.append('\r');
						case 't' -> sb.append('\t');
						case 'b' -> sb.append('\b');
						case 'f' -> sb.append('\f');
						case 'u' -> {
							final String hex = src.substring(pos, pos + 4);
							sb.append((char) Integer.parseInt(hex, 16));
							pos += 4;
						}
						default -> throw new IllegalArgumentException("bad escape \\" + esc);
					}
				} else {
					sb.append(c);
				}
			}
		}

		private Boolean readBoolean() {
			if (src.startsWith("true", pos)) {
				pos += 4;
				return Boolean.TRUE;
			}
			if (src.startsWith("false", pos)) {
				pos += 5;
				return Boolean.FALSE;
			}
			throw new IllegalArgumentException("invalid token at index " + pos);
		}

		private Object readNull() {
			if (src.startsWith("null", pos)) {
				pos += 4;
				return null;
			}
			throw new IllegalArgumentException("invalid token at index " + pos);
		}

		private String readNumber() {
			final int start = pos;
			while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
				pos++;
			}
			if (pos == start) {
				throw new IllegalArgumentException("invalid value at index " + pos);
			}
			return src.substring(start, pos);
		}

		private char peek() {
			return atEnd() ? '\0' : src.charAt(pos);
		}

		private char next() {
			if (atEnd()) {
				throw new IllegalArgumentException("unexpected end of input");
			}
			return src.charAt(pos++);
		}

		private void expect(final char c) {
			final char actual = next();
			if (actual != c) {
				throw new IllegalArgumentException(
						"expected '" + c + "' but found '" + actual + "' at index " + (pos - 1));
			}
		}
	}
}
