package com.u1.servicepal.internal.windows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny, dependency-free JSON reader/writer — just enough for the Windows sidecar file (which we
 * both write and read, so the format is fully under our control). Objects map to
 * {@link LinkedHashMap}, arrays to {@link ArrayList}, strings to {@link String}, numbers to
 * {@link Long}/{@link Double}, plus {@link Boolean} and {@code null}. Not a general-purpose JSON
 * library — it covers the value shapes the sidecar uses and rejects malformed input.
 */
final class Json {

	private Json() {
	}

	// ---- writing ----

	static String write(final Object value) {
		final StringBuilder sb = new StringBuilder();
		writeValue(sb, value, 0);
		return sb.toString();
	}

	private static void writeValue(final StringBuilder sb, final Object value, final int indent) {
		if (value == null) {
			sb.append("null");
		} else if (value instanceof String s) {
			writeString(sb, s);
		} else if (value instanceof Boolean || value instanceof Number) {
			sb.append(value);
		} else if (value instanceof Map<?, ?> map) {
			writeObject(sb, map, indent);
		} else if (value instanceof List<?> list) {
			writeArray(sb, list, indent);
		} else {
			throw new IllegalArgumentException("unsupported JSON value: " + value.getClass());
		}
	}

	private static void writeObject(final StringBuilder sb, final Map<?, ?> map, final int indent) {
		if (map.isEmpty()) {
			sb.append("{}");
			return;
		}
		sb.append("{\n");
		int i = 0;
		for (final Map.Entry<?, ?> e : map.entrySet()) {
			indent(sb, indent + 1);
			writeString(sb, String.valueOf(e.getKey()));
			sb.append(": ");
			writeValue(sb, e.getValue(), indent + 1);
			if (++i < map.size()) {
				sb.append(',');
			}
			sb.append('\n');
		}
		indent(sb, indent);
		sb.append('}');
	}

	private static void writeArray(final StringBuilder sb, final List<?> list, final int indent) {
		if (list.isEmpty()) {
			sb.append("[]");
			return;
		}
		sb.append("[\n");
		for (int i = 0; i < list.size(); i++) {
			indent(sb, indent + 1);
			writeValue(sb, list.get(i), indent + 1);
			if (i + 1 < list.size()) {
				sb.append(',');
			}
			sb.append('\n');
		}
		indent(sb, indent);
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

	private static void indent(final StringBuilder sb, final int level) {
		for (int i = 0; i < level; i++) {
			sb.append('\t');
		}
	}

	// ---- parsing ----

	static Object parse(final String text) {
		final Parser p = new Parser(text);
		p.skipWhitespace();
		final Object value = p.readValue();
		p.skipWhitespace();
		if (!p.atEnd()) {
			throw new IllegalArgumentException("trailing characters at position " + p.pos);
		}
		return value;
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

		Object readValue() {
			skipWhitespace();
			if (atEnd()) {
				throw new IllegalArgumentException("unexpected end of input");
			}
			final char c = s.charAt(pos);
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
					throw new IllegalArgumentException("expected ',' or '}' at " + (pos - 1));
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
					throw new IllegalArgumentException("expected ',' or ']' at " + (pos - 1));
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
				final char c = s.charAt(pos++);
				if (c == '"') {
					return sb.toString();
				}
				if (c == '\\') {
					final char esc = s.charAt(pos++);
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
							final String hex = s.substring(pos, pos + 4);
							pos += 4;
							sb.append((char) Integer.parseInt(hex, 16));
						}
						default -> throw new IllegalArgumentException("bad escape \\" + esc);
					}
				} else {
					sb.append(c);
				}
			}
		}

		private Boolean readBoolean() {
			if (s.startsWith("true", pos)) {
				pos += 4;
				return Boolean.TRUE;
			}
			if (s.startsWith("false", pos)) {
				pos += 5;
				return Boolean.FALSE;
			}
			throw new IllegalArgumentException("invalid literal at " + pos);
		}

		private Object readNull() {
			if (s.startsWith("null", pos)) {
				pos += 4;
				return null;
			}
			throw new IllegalArgumentException("invalid literal at " + pos);
		}

		private Object readNumber() {
			final int start = pos;
			boolean floating = false;
			while (pos < s.length()) {
				final char c = s.charAt(pos);
				if (c == '-' || c == '+' || (c >= '0' && c <= '9')) {
					pos++;
				} else if (c == '.' || c == 'e' || c == 'E') {
					floating = true;
					pos++;
				} else {
					break;
				}
			}
			final String num = s.substring(start, pos);
			if (num.isEmpty()) {
				throw new IllegalArgumentException("invalid number at " + start);
			}
			return floating ? (Object) Double.valueOf(num) : (Object) Long.valueOf(num);
		}

		private char peek() {
			if (atEnd()) {
				throw new IllegalArgumentException("unexpected end of input");
			}
			return s.charAt(pos);
		}

		private char next() {
			if (atEnd()) {
				throw new IllegalArgumentException("unexpected end of input");
			}
			return s.charAt(pos++);
		}

		private void expect(final char c) {
			if (atEnd() || s.charAt(pos) != c) {
				throw new IllegalArgumentException("expected '" + c + "' at " + pos);
			}
			pos++;
		}
	}
}
