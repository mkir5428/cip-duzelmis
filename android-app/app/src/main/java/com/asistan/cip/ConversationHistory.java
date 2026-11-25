package com.asistan.cip;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class ConversationHistory {
    private static final int MAX_MESSAGES = 40;
    private static final int MAX_CONTENT_CHARACTERS = 2000;
    private static final ConversationHistory SHARED = new ConversationHistory();
    private static final Pattern CLEAR_WORDS =
            Pattern.compile("\\b(temizle|sifirla|sil|unut|resetle)\\b");
    private static final Pattern MEMORY_WORDS =
            Pattern.compile("\\b(hafiza\\w*|sohbet\\w*|gecmis\\w*)\\b");

    private final List<JSONObject> messages = new ArrayList<>();

    static ConversationHistory shared() {
        return SHARED;
    }

    synchronized String toJson() {
        return new JSONArray(messages).toString();
    }

    synchronized void addTurn(String userText, String assistantText) {
        String normalized = WakeCommandParser.normalize(userText);
        if (CLEAR_WORDS.matcher(normalized).find() && MEMORY_WORDS.matcher(normalized).find()) {
            messages.clear();
            return;
        }
        addMessage("user", userText);
        addMessage("assistant", assistantText);
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
    }

    private void addMessage(String role, String content) {
        try {
            messages.add(new JSONObject()
                    .put("role", role)
                    .put("content", content.substring(0, Math.min(content.length(), MAX_CONTENT_CHARACTERS))));
        } catch (JSONException error) {
            throw new IllegalStateException("Konuşma geçmişi hazırlanamadı.", error);
        }
    }
}
