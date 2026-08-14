package io.github.eolivelli.nb5visualizer;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void parsesManifestLine() {
        String line = "{\"metric\":\"result_success\",\"labels\":{\"activity\":\"main\","
                + "\"op\":\"main_select\"},\"file\":\"a.csv\",\"first_seen_ms\":1786721935640,\"type\":\"timer\"}";
        Map<String, Object> obj = Json.parseObject(line);
        assertEquals("result_success", obj.get("metric"));
        assertEquals("timer", obj.get("type"));
        assertEquals(1786721935640L, obj.get("first_seen_ms"));
        Map<?, ?> labels = (Map<?, ?>) obj.get("labels");
        assertEquals("main", labels.get("activity"));
        assertEquals("main_select", labels.get("op"));
    }

    @Test
    void parsesNestedStructuresAndLiterals() {
        Map<String, Object> obj = Json.parseObject(
                "{\"a\":[1,2.5,-3e2,true,false,null],\"b\":{\"c\":\"x\\ny\"},\"d\":\"\\u0041\"}");
        List<?> a = (List<?>) obj.get("a");
        assertEquals(1L, a.get(0));
        assertEquals(2.5, a.get(1));
        assertEquals(-300.0, a.get(2));
        assertEquals(Boolean.TRUE, a.get(3));
        assertEquals(Boolean.FALSE, a.get(4));
        assertEquals(null, a.get(5));
        assertEquals("x\ny", ((Map<?, ?>) obj.get("b")).get("c"));
        assertEquals("A", obj.get("d"));
    }

    @Test
    void rejectsTrailingContent() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} junk"));
    }

    @Test
    void writesRoundTrippableJson() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("name", "it \"quotes\" and \\slashes\\");
        obj.put("count", 42L);
        obj.put("rate", 1.5);
        obj.put("nan", Double.NaN);
        obj.put("arr", new double[]{1, 2.25, Double.NaN});
        obj.put("longs", new long[]{7, 8});
        String json = Json.write(obj);
        Map<String, Object> back = Json.parseObject(json);
        assertEquals("it \"quotes\" and \\slashes\\", back.get("name"));
        assertEquals(42L, back.get("count"));
        assertEquals(1.5, back.get("rate"));
        assertEquals(null, back.get("nan"));
        assertEquals(java.util.Arrays.asList(1L, 2.25, null), back.get("arr"));
        assertEquals(List.of(7L, 8L), back.get("longs"));
    }

    @Test
    void escapesScriptClosingSequences() {
        // the report embeds JSON inside a <script> tag, so "<" must never appear raw
        String json = Json.write(Map.of("x", "</script><b>"));
        assertFalse(json.contains("</script>"));
        assertTrue(json.contains("\\u003c"));
        assertEquals("</script><b>", Json.parseObject(json).get("x"));
    }
}
