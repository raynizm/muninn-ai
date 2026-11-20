package com.muninn.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.muninn.provider.adapter.ChatAdapter;
import com.muninn.provider.adapter.ConnectionTestResult;
import com.muninn.provider.adapter.DiscoveredModel;
import com.muninn.provider.adapter.OpenAiCompatibleAdapter;
import com.muninn.provider.adapter.ProviderConnection;
import com.muninn.provider.adapter.TranscriptResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenAiCompatibleAdapterTest {

    private static final FakeOpenAi SERVER = new FakeOpenAi();
    private static final String KEY = "sk-adapter-key";

    private final OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter();
    private final ProviderConnection conn = new ProviderConnection(SERVER.baseUrl() + "/", KEY);

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @Test
    void discoverParsesV1Models() {
        List<DiscoveredModel> models = adapter.discover(conn);
        assertThat(models)
                .extracting(DiscoveredModel::id)
                .containsExactly("gpt-4o-mini", "whisper-1", "text-embedding-3-small");
        assertThat(models)
                .extracting(DiscoveredModel::capabilities)
                .containsExactly(List.of("chat"), List.of("transcription"), List.of("embeddings"));
        assertThat(SERVER.last().path()).isEqualTo("/v1/models");
    }

    @Test
    void chatSendsBearerAndParsesChoice() {
        ChatAdapter.ChatResponse r = adapter.chat(new ChatAdapter.ChatRequest(
                conn, "gpt-4o-mini", List.of(new ChatAdapter.ChatMessage("user", "hi")), false));
        assertThat(r.content()).isEqualTo("hello there");
        FakeOpenAi.Received req = SERVER.last();
        assertThat(req.path()).isEqualTo("/v1/chat/completions");
        assertThat(req.authorization()).isEqualTo("Bearer " + KEY);
        assertThat(req.body()).contains("\"model\":\"gpt-4o-mini\"").contains("\"content\":\"hi\"");
    }

    @Test
    void transcribePostsMultipart(@TempDir Path dir) throws Exception {
        Path audio = dir.resolve("a.webm");
        Files.write(audio, "AUDIOBYTES".getBytes(StandardCharsets.UTF_8));
        TranscriptResult r = adapter.transcribe(conn, audio, "whisper-1");
        assertThat(r.text()).isEqualTo("hi all");
        assertThat(r.segments()).hasSize(2);
        assertThat(r.segments().get(1).start()).isEqualTo(1.5);
        assertThat(r.segments().get(1).text()).isEqualTo("all");
        FakeOpenAi.Received req = SERVER.last();
        assertThat(req.contentType()).startsWith("multipart/form-data");
        assertThat(req.authorization()).isEqualTo("Bearer " + KEY);
        assertThat(req.body())
                .contains("name=\"model\"")
                .contains("whisper-1")
                .contains("name=\"file\"")
                .contains("AUDIOBYTES");
    }

    @Test
    void connectionTestOkAndFailureWithoutSecret() {
        assertThat(adapter.test(conn).ok()).isTrue();
        SERVER.requireKey("other");
        try {
            ConnectionTestResult bad = adapter.test(conn);
            assertThat(bad.ok()).isFalse();
            assertThat(bad.message()).contains("401").doesNotContain(KEY);
        } finally {
            SERVER.requireKey(null);
        }
    }
}
