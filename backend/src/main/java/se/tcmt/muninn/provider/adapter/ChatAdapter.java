package se.tcmt.muninn.provider.adapter;

import java.util.List;

public interface ChatAdapter {

    ChatResponse chat(ChatRequest r);

    record ChatMessage(String role, String content) {}

    /** {@code jsonMode} asks for a JSON object response (response_format json_object). */
    record ChatRequest(ProviderConnection connection, String model, List<ChatMessage> messages, boolean jsonMode) {}

    record ChatResponse(String content, String model) {}
}
