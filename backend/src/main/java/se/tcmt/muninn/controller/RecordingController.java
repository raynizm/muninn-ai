package se.tcmt.muninn.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.model.ProcessingStatus;
import se.tcmt.muninn.model.Recording;
import se.tcmt.muninn.service.AudioStore;
import se.tcmt.muninn.service.RecordingService;
import se.tcmt.muninn.service.RecordingService.RecordingDetail;
import se.tcmt.muninn.service.RecordingService.UploadResult;
import tools.jackson.databind.JsonNode;

/**
 * The signed-in user's recordings. Everything is scoped to the caller: another user's id answers 404 for
 * GET, audio and DELETE, for admins too. PUT is different because recording ids are a global primary key
 * chosen by the client: an id already owned by someone else answers 409 with the same body as "same id,
 * different bytes", so the response never reveals that the id belongs to another user.
 */
@RestController
@RequestMapping("/api/recordings")
@RequiredArgsConstructor
public class RecordingController {

    private final RecordingService service;
    private final AudioStore audio;

    public record RecordingView(
            UUID id,
            String title,
            Instant createdAt,
            Instant updatedAt,
            Long durationMs,
            String mime,
            Long sizeBytes,
            String uploadStatus,
            ProcessingStatus processingStatus,
            String lastError) {}

    public record TranscriptView(String text, JsonNode segments) {}

    public record SummaryView(String text, JsonNode actionItems) {}

    public record RecordingDetailView(
            UUID id,
            String title,
            Instant createdAt,
            Instant updatedAt,
            Long durationMs,
            String mime,
            Long sizeBytes,
            String uploadStatus,
            ProcessingStatus processingStatus,
            String lastError,
            TranscriptView transcript,
            SummaryView summary) {}

    /**
     * Raw audio body. 201 on first upload, 200 for an identical repeat; see {@link RecordingService#upload} for
     * the error statuses. The body is streamed to disk, never buffered in memory.
     */
    @PutMapping("/{id}")
    public ResponseEntity<RecordingView> upload(
            @AuthenticationPrincipal AppUser user,
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestHeader(value = "X-Content-SHA256", required = false) String sha256,
            @RequestHeader(value = "X-Recording-Created-At", required = false) String createdAt,
            @RequestHeader(value = "X-Recording-Duration-Ms", required = false) String durationMs,
            @RequestHeader(value = "X-Recording-Title", required = false) String title,
            HttpServletRequest request)
            throws IOException {
        RecordingService.normalizeMime(contentType); // 415 before anything else
        long length = request.getContentLengthLong();
        if (length > service.maxUploadBytes()) {
            throw new ResponseStatusException(
                    HttpStatus.CONTENT_TOO_LARGE, "audio exceeds " + service.maxUploadBytes() + " bytes");
        }
        if (length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty body");
        }
        try (InputStream body = request.getInputStream()) {
            UploadResult r = service.upload(
                    user.id(),
                    id,
                    body,
                    sha256,
                    contentType,
                    parseInstant(createdAt),
                    parseDuration(durationMs),
                    title);
            return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(view(r.recording()));
        }
    }

    @GetMapping
    public List<RecordingView> list(
            @AuthenticationPrincipal AppUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedSince) {
        return service.list(user.id(), updatedSince).stream()
                .map(RecordingController::view)
                .toList();
    }

    @GetMapping("/{id}")
    public RecordingDetailView get(@AuthenticationPrincipal AppUser user, @PathVariable UUID id) {
        return service.detail(user.id(), id).map(RecordingController::view).orElseThrow(RecordingController::notFound);
    }

    /** Streams the stored file; Spring serves {@code Range} requests (206) for a {@link Resource} body. */
    @GetMapping("/{id}/audio")
    public ResponseEntity<Resource> audio(@AuthenticationPrincipal AppUser user, @PathVariable UUID id) {
        Recording r = service.get(user.id(), id).orElseThrow(RecordingController::notFound);
        Path file = audio.path(user.id(), id);
        if (!Files.isRegularFile(file)) {
            throw notFound();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(r.mime()))
                .body(new FileSystemResource(file));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AppUser user, @PathVariable UUID id) {
        if (!service.delete(user.id(), id)) {
            throw notFound();
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Recording-Created-At must be ISO-8601");
        }
    }

    private static Long parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Recording-Duration-Ms must be an integer");
        }
    }

    static RecordingView view(Recording r) {
        return new RecordingView(
                r.id(),
                r.title(),
                r.createdAt(),
                r.updatedAt(),
                r.durationMs(),
                r.mime(),
                r.sizeBytes(),
                r.uploadStatus(),
                r.processingStatus(),
                r.lastError());
    }

    private static RecordingDetailView view(RecordingDetail d) {
        Recording r = d.recording();
        return new RecordingDetailView(
                r.id(),
                r.title(),
                r.createdAt(),
                r.updatedAt(),
                r.durationMs(),
                r.mime(),
                r.sizeBytes(),
                r.uploadStatus(),
                r.processingStatus(),
                r.lastError(),
                d.transcript() == null
                        ? null
                        : new TranscriptView(
                                d.transcript().text(), d.transcript().segments()),
                d.summary() == null
                        ? null
                        : new SummaryView(d.summary().text(), d.summary().actionItems()));
    }
}
