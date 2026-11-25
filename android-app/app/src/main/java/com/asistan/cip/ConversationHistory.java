package com.asistan.cip;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
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

    static final class Entry {
        final String sender;
        final String text;

        Entry(String sender, String text) {
            this.sender = sender;
            this.text = text;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    static ConversationHistory shared() {
        return SHARED;
    }

    static ConversationHistory fromJson(String serialized) throws JSONException {
        ConversationHistory history = new ConversationHistory();
        if (serialized == null || serialized.isEmpty()) {
            return history;
        }
        JSONArray messages = new JSONArray(serialized);
        int first = Math.max(0, messages.length() - MAX_MESSAGES);
        for (int i = first; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            String role = message.getString("role");
            String sender = "user".equals(role) ? "Sen"
                    : "assistant".equals(role) ? "Çip" : null;
            if (sender != null) {
                history.append(sender, message.getString("content"));
            }
        }
        return history;
    }

    synchronized void append(String sender, String text) {
        if (sender == null || text == null) {
            return;
        }
        entries.add(new Entry(sender,
                text.substring(0, Math.min(text.length(), MAX_CONTENT_CHARACTERS))));
        if (entries.size() > MAX_MESSAGES) {
            entries.remove(0);
        }
    }

    synchronized void addTurn(String userText, String assistantText) {
        String normalized = WakeCommandParser.normalize(userText);
        if (CLEAR_WORDS.matcher(normalized).find() && MEMORY_WORDS.matcher(normalized).find()) {
            entries.clear();
            return;
        }
        append("Sen", userText);
        append("Çip", assistantText);
    }

    synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    synchronized String toJson() {
        JSONArray messages = new JSONArray();
        for (Entry entry : entries) {
            String role = "Sen".equals(entry.sender) ? "user"
                    : "Çip".equals(entry.sender) ? "assistant" : null;
            if (role == null) {
                continue;
            }
            try {
                messages.put(new JSONObject()
                        .put("role", role)
                        .put("content", entry.text));
            } catch (JSONException error) {
                throw new IllegalStateException("Konuşma geçmişi hazırlanamadı.", error);
            }
        }
        return messages.toString();
    }
}
