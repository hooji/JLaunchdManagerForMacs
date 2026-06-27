package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

	@Test
	@SuppressWarnings("unchecked")
	void roundTripsNestedStructures() {
		final Map<String, Object> root = new LinkedHashMap<>();
		root.put("name", "back\\slash and \"quote\"");
		root.put("count", 3L);
		root.put("enabled", true);
		root.put("absent", null);
		root.put("list", List.of("a", "b"));
		final Map<String, Object> nested = new LinkedHashMap<>();
		nested.put("k", "v");
		root.put("obj", nested);

		final Object parsed = Json.parse(Json.write(root));
		final Map<String, Object> out = (Map<String, Object>) parsed;
		assertEquals("back\\slash and \"quote\"", out.get("name"));
		assertEquals(3L, out.get("count"));
		assertEquals(Boolean.TRUE, out.get("enabled"));
		assertTrue(out.containsKey("absent"));
		assertEquals(List.of("a", "b"), out.get("list"));
		assertInstanceOf(Map.class, out.get("obj"));
	}

	@Test
	void parsesEmptyContainers() {
		assertEquals(Map.of(), Json.parse("{}"));
		assertEquals(List.of(), Json.parse("[]"));
	}

	@Test
	void rejectsMalformedInput() {
		assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("{} trailing"));
	}
}
