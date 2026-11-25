package com.asistan.cip;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.junit.Test;

public class ConversationHistoryTest {
    @Test
    public void serializesOnlyCompleteUserAssistantTurns() throws Exception {
        ConversationHistory history = new ConversationHistory();
        history.addTurn("Merhaba", "Selam!");

        JSONArray messages = new JSONArray(history.toJson());
        assertEquals(2, messages.length());
        assertEquals("user", messages.getJSONObject(0).getString("role"));
        assertEquals("Merhaba", messages.getJSONObject(0).getString("content"));
        assertEquals("assistant", messages.getJSONObject(1).getString("role"));
        assertEquals("Selam!", messages.getJSONObject(1).getString("content"));
    }

    @Test
    public void preservesMessagesAndSpeakerAcrossSerialization() throws Exception {
        ConversationHistory history = new ConversationHistory();
        history.addTurn("Merhaba", "Selam!");

        ConversationHistory restored = ConversationHistory.fromJson(history.toJson());

        assertEquals(2, restored.entries().size());
        assertEquals("Sen", restored.entries().get(0).sender);
        assertEquals("Merhaba", restored.entries().get(0).text);
        assertEquals("Çip", restored.entries().get(1).sender);
    }

    @Test
    public void keepsOnlyTheMostRecentTwentyTurns() throws Exception {
        ConversationHistory history = new ConversationHistory();
        for (int turn = 0; turn < 25; turn++) {
            history.addTurn("User " + turn, "Assistant " + turn);
        }

        JSONArray messages = new JSONArray(history.toJson());
        assertEquals(40, messages.length());
        assertEquals("User 5", messages.getJSONObject(0).getString("content"));
        assertEquals("Assistant 24", messages.getJSONObject(39).getString("content"));
    }

    @Test
    public void limitsEachContextMessageToTheServerHistoryLimit() throws Exception {
        ConversationHistory history = new ConversationHistory();
        history.addTurn("U".repeat(2500), "Yanıt");

        JSONArray messages = new JSONArray(history.toJson());
        assertEquals(2000, messages.getJSONObject(0).getString("content").length());
    }

    @Test
    public void memoryClearRequestRemovesEarlierTurns() throws Exception {
        ConversationHistory history = new ConversationHistory();
        history.addTurn("Benim adım Deniz", "Memnun oldum.");
        history.addTurn("Sohbet hafızasını temizle", "Yeni bir sayfa açtık.");

        assertEquals(0, new JSONArray(history.toJson()).length());
    }
}
