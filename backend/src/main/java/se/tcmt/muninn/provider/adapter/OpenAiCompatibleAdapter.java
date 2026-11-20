package se.tcmt.muninn.provider.adapter;

import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Talks to any OpenAI-compatible server ({@code {baseUrl}/v1/...}). Timeouts: 10s connect, 120s
 * read. Failure messages carry only a status code or an exception class, never response bodies,
 * URLs or keys.
 */
@Component
public class OpenAiCompatibleAdapter
    implements ChatAdapter, TranscriptionAdapter, ModelDiscoveryAdapter {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final RestClient client;

  /** Creates the adapter with a client using 10s connect and 120s read timeouts. */
  public OpenAiCompatibleAdapter() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(10));
    factory.setReadTimeout(Duration.ofSeconds(120));
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  /** Lists models from {@code /v1/models} and guesses their capabilities. */
  @Override
  public List<DiscoveredModel> discover(ProviderConnection c) {
    try {
      JsonNode root = get(c, "/v1/model");
      List<DiscoveredModel> out = new ArrayList<>();
      for (JsonNode m : root.path("data")) {
        String id = m.path("id").asText("");
        if (!id.isBlank()) {
          out.add(new DiscoveredModel(id, guessCapabilities(id)));
        }
      }
      return out;
    } catch (RuntimeException e) {
      throw failure(e);
    }
  }

  /** Calls {@code /v1/models} and reports the model count or the failure reason. */
  @Override
  public ConnectionTestResult test(ProviderConnection c) {
    try {
      int n = discover(c).size();
      return new ConnectionTestResult(true, "ok, " + n + " model");
    } catch (ResponseStatusException e) {
      return new ConnectionTestResult(false, e.getReason());
    }
  }

  /** Posts to {@code /v1/chat/completions} and returns the first choice. */
  @Override
  public ChatResponse chat(ChatRequest r) {
    ObjectNode body = JSON.createObjectNode().put("model", r.model());
    ArrayNode messages = body.putArray("messages");
    r.messages()
        .forEach(m -> messages.addObject().put("role", m.role()).put("content", m.content()));
    if (r.jsonMode()) {
      body.putObject("response_format").put("type", "json_object");
    }
    try {
      JsonNode res =
          client
              .post()
              .uri(url(r.connection(), "/v1/chat/completions"))
              .headers(h -> auth(h, r.connection()))
              .contentType(MediaType.APPLICATION_JSON)
              .body(body.toString())
              .retrieve()
              .body(JsonNode.class);
      JsonNode content = res == null ? null : res.at("/choices/0/message/content");
      if (content == null || content.isMissingNode() || content.isNull()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_GATEWAY, "provider returned no chat choice");
      }
      return new ChatResponse(content.asText(), res.path("model").asText(r.model()));
    } catch (ResponseStatusException e) {
      throw e;
    } catch (RuntimeException e) {
      throw failure(e);
    }
  }

  /** Posts the audio to {@code /v1/audio/transcriptions} and returns text with segments. */
  @Override
  public TranscriptResult transcribe(ProviderConnection c, Path audio, String model) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("model", model);
    form.add("response_format", "verbose_json");
    form.add("file", new FileSystemResource(audio));
    try {
      JsonNode res =
          client
              .post()
              .uri(url(c, "/v1/audio/transcriptions"))
              .headers(h -> auth(h, c))
              .contentType(MediaType.MULTIPART_FORM_DATA)
              .body(form)
              .retrieve()
              .body(JsonNode.class);
      if (res == null || !res.hasNonNull("text")) {
        throw new ResponseStatusException(
            HttpStatus.BAD_GATEWAY, "provider returned no transcript");
      }
      List<TranscriptResult.Segment> segments = new ArrayList<>();
      for (JsonNode s : res.path("segments")) {
        segments.add(
            new TranscriptResult.Segment(
                s.path("start").asDouble(), s.path("end").asDouble(), s.path("text").asText("")));
      }
      return new TranscriptResult(res.get("text").asText(), segments);
    } catch (ResponseStatusException e) {
      throw e;
    } catch (RuntimeException e) {
      throw failure(e);
    }
  }

  /** Heuristic for discovered model; editable afterwards. */
  static List<String> guessCapabilities(String modelId) {
    String id = modelId.toLowerCase(Locale.ROOT);
    if (id.contains("whisper")) {
      return List.of("transcription");
    }
    if (id.contains("embed")) {
      return List.of("embeddings");
    }
    return List.of("chat");
  }

  /** GETs a JSON document from the provider; 502 when the body is empty. */
  private JsonNode get(ProviderConnection c, String path) {
    JsonNode res =
        client.get().uri(url(c, path)).headers(h -> auth(h, c)).retrieve().body(JsonNode.class);
    if (res == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "provider returned an empty response");
    }
    return res;
  }

  /** Joins the base URL (without trailing slashes) and the path. */
  private static String url(ProviderConnection c, String path) {
    String base = c.baseUrl();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + path;
  }

  /** Adds the bearer header when an API key is set. */
  private static void auth(HttpHeaders h, ProviderConnection c) {
    if (c.apiKey() != null && !c.apiKey().isEmpty()) {
      h.setBearerAuth(c.apiKey());
    }
  }

  /** Maps a client failure to a 502 with a short, secret-free message. */
  private static ResponseStatusException failure(RuntimeException e) {
    if (e instanceof ResponseStatusException rse) {
      return rse;
    }
    String message;
    if (e instanceof RestClientResponseException r) {
      message = "provider answered HTTP " + r.getStatusCode().value();
    } else if (e.getCause() instanceof SocketTimeoutException) {
      message = "provider timed out";
    } else if (e instanceof org.springframework.web.client.ResourceAccessException) {
      message = "cannot connect to provider";
    } else {
      message = "provider request failed (" + e.getClass().getSimpleName() + ")";
    }
    return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
  }
}
