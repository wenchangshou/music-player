package com.tidalgui.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;

public class AiProxyTest {
    private static JSONObject chat(String role, String content) throws Exception {
        return new JSONObject().put("messages", new JSONArray().put(new JSONObject().put("role", role).put("content", content)));
    }

    @Test
    public void requestKeepsOnlyConversationAndClampsLimits() throws Exception {
        JSONObject input = chat("user", "hi").put("model", "evil").put("endpoint", "http://10.0.0.1")
                .put("temperature", 9).put("max_tokens", 99999);
        JSONObject body = AiProxy.requestBody(input, "deepseek-chat", "https://api.deepseek.com");
        assertEquals("deepseek-chat", body.getString("model"));
        assertEquals(1.5, body.getDouble("temperature"), 0.0001);
        assertEquals(AiProxy.MAX_TOKENS, body.getInt("max_tokens"));
        assertFalse(body.has("endpoint"));
        assertFalse(body.has("thinking"));
    }

    @Test
    public void volcesDisablesThinkingAndReasonersSkipTemperature() throws Exception {
        JSONObject ark = AiProxy.requestBody(chat("user", "hi").put("temperature", 0.5), "m", "https://ark.cn-beijing.volces.com/api/v3");
        assertTrue(ark.has("thinking"));
        JSONObject reasoner = AiProxy.requestBody(chat("user", "hi").put("temperature", 0.5), "deepseek-reasoner", "https://x");
        assertFalse(reasoner.has("temperature"));
    }

    @Test
    public void rejectsBadMessages() {
        assertThrows(IOException.class, () -> AiProxy.requestBody(chat("tool", "x"), "m", "https://x"));
        assertThrows(IOException.class, () -> AiProxy.requestBody(new JSONObject(), "m", "https://x"));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i <= AiProxy.MAX_CHARS; i++) big.append('a');
        assertThrows(IOException.class, () -> AiProxy.requestBody(chat("user", big.toString()), "m", "https://x"));
    }

    @Test
    public void replyTextReadsCommonShapes() throws Exception {
        assertEquals("ok", AiProxy.replyText(new JSONObject("{\"choices\":[{\"message\":{\"content\":\" ok \"}}]}")));
        assertEquals("ab", AiProxy.replyText(new JSONObject("{\"choices\":[{\"message\":{\"content\":[{\"text\":\"a\"},{\"text\":\"b\"}]}}]}")));
        assertEquals("think", AiProxy.replyText(new JSONObject("{\"choices\":[{\"message\":{\"content\":\"\",\"reasoning_content\":\"think\"}}]}")));
        assertEquals("", AiProxy.replyText(new JSONObject("{}")));
    }

    @Test
    public void completionsUrlAppendsPathOnce() {
        assertEquals("https://a/v1/chat/completions", AiProxy.completionsUrl("https://a/v1/"));
        assertEquals("https://a/v1/chat/completions", AiProxy.completionsUrl("https://a/v1/chat/completions"));
    }
}
