package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.u1.servicepal.DefinitionIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

	@Test
	@SuppressWarnings("unchecked")
	void roundTripsObjectsArraysAndStrings() {
		final Map<String, Object> obj = new LinkedHashMap<>();
		obj.put("id", "com.example.api");
		obj.put("command", List.of("C:\\app\\api.exe", "--port", "8080"));
		final Map<String, Object> env = new LinkedHashMap<>();
		env.put("LOG", "info");
		env.put("HOME", "C:\\Users\\svc");
		obj.put("environment", env);

		final Map<String, Object> back = Json.parseObject(Json.write(obj));
		assertEquals("com.example.api", back.get("id"));
		assertEquals(List.of("C:\\app\\api.exe", "--port", "8080"), back.get("command"));
		assertEquals("info", ((Map<String, Object>) back.get("environment")).get("LOG"));
		assertEquals("C:\\Users\\svc", ((Map<String, Object>) back.get("environment")).get("HOME"));
	}

	@Test
	void escapesAndUnescapesSpecialCharacters() {
		final Map<String, Object> obj = new LinkedHashMap<>();
		// Backslashes (Windows paths), quotes, newlines, tabs, and a control char.
		obj.put("tricky", "a\\b\"c\nd\tef");
		final Map<String, Object> back = Json.parseObject(Json.write(obj));
		assertEquals("a\\b\"c\nd\tef", back.get("tricky"));
	}

	@Test
	void omitsNullValues() {
		final Map<String, Object> obj = new LinkedHashMap<>();
		obj.put("present", "yes");
		obj.put("absent", null);
		final String json = Json.write(obj);
		assertFalse(json.contains("absent"), "null-valued keys are omitted");
		assertEquals("yes", Json.parseObject(json).get("present"));
	}

	@Test
	void parsesEmptyContainers() {
		final Map<String, Object> back = Json.parseObject("{ \"a\": {}, \"b\": [] }");
		assertEquals(Map.of(), back.get("a"));
		assertEquals(List.of(), back.get("b"));
	}

	@Test
	void rejectsMalformedJson() {
		assertThrows(DefinitionIOException.class, () -> Json.parseObject("{ \"a\": }"));
		assertThrows(DefinitionIOException.class, () -> Json.parseObject("{ \"a\": \"unterminated"));
		assertThrows(DefinitionIOException.class, () -> Json.parseObject("[1,2,3]"));   // not an object
		assertThrows(DefinitionIOException.class, () -> Json.parseObject("{} trailing"));
	}
}
