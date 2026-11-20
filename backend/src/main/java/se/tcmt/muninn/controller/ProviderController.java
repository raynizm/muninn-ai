package se.tcmt.muninn.controller;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.dto.ProviderRequest;
import se.tcmt.muninn.model.AiModel;
import se.tcmt.muninn.model.AiProvider;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.provider.adapter.ConnectionTestResult;
import se.tcmt.muninn.service.ProviderService;

/** Provider CRUD for the signed-in user; anything not owned (or SYSTEM for non-admins) answers 404. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProviderController {

    private final ProviderService service;

    public record LastTest(boolean ok, String message, Instant at) {}

    public record ProviderView(
            UUID id,
            String scope,
            String name,
            String baseUrl,
            boolean enabled,
            boolean keySet,
            boolean isLocal,
            Instant createdAt,
            LastTest lastTest) {}

    public record ModelView(
            UUID id, UUID providerId, String modelIdentifier, List<String> capabilities, boolean enabled) {}

    public record ModelUpdate(List<String> capabilities, Boolean enabled) {}

    @GetMapping("/providers")
    public List<ProviderView> list(@AuthenticationPrincipal AppUser user) {
        return service.listFor(user).stream().map(ProviderController::view).toList();
    }

    @PostMapping("/providers")
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderView create(@AuthenticationPrincipal AppUser user, @RequestBody ProviderRequest req) {
        String scope = req.scope() == null ? "USER" : req.scope().toUpperCase(Locale.ROOT);
        boolean system =
                switch (scope) {
                    case "SYSTEM", "GLOBAL" -> true;
                    case "USER" -> false;
                    default -> throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "scope must be SYSTEM or USER");
                };
        if (system && user.role() != AppUser.Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only admins can create system providers");
        }
        return view(service.create(system ? null : user.id(), req));
    }

    @GetMapping("/providers/{id}")
    public ProviderView get(@AuthenticationPrincipal AppUser user, @PathVariable UUID id) {
        return view(service.requireManageable(user, id));
    }

    @PutMapping("/providers/{id}")
    public ProviderView update(
            @AuthenticationPrincipal AppUser user, @PathVariable UUID id, @RequestBody ProviderRequest req) {
        service.requireManageable(user, id);
        return view(service.update(id, req));
    }

    @DeleteMapping("/providers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AppUser user, @PathVariable UUID id) {
        service.requireManageable(user, id);
        service.delete(id);
    }

    @PostMapping("/providers/{id}/test")
    public ConnectionTestResult test(@AuthenticationPrincipal AppUser user, @PathVariable UUID id) {
        service.requireManageable(user, id);
        return service.test(id);
    }

    @PostMapping("/providers/{id}/discover")
    public List<ModelView> discover(@AuthenticationPrincipal AppUser user, @PathVariable UUID id) {
        service.requireManageable(user, id);
        return service.discover(id).stream().map(ProviderController::view).toList();
    }

    @GetMapping("/providers/{id}/model")
    public List<ModelView> models(@AuthenticationPrincipal AppUser user, @PathVariable UUID id) {
        service.requireManageable(user, id);
        return service.models(id).stream().map(ProviderController::view).toList();
    }

    @PutMapping("/model/{id}")
    public ModelView updateModel(
            @AuthenticationPrincipal AppUser user, @PathVariable UUID id, @RequestBody ModelUpdate req) {
        service.requireManageableModel(user, id);
        return view(service.updateModel(id, req.capabilities(), req.enabled()));
    }

    /** Secret-free view of a provider (shared with the setup wizard). */
    public static ProviderView view(AiProvider p) {
        LastTest last = p.lastTestedAt() == null
                ? null
                : new LastTest(Boolean.TRUE.equals(p.lastTestOk()), p.lastTestMessage(), p.lastTestedAt());
        return new ProviderView(
                p.id(),
                p.isSystem() ? "SYSTEM" : "USER",
                p.name(),
                p.baseUrl(),
                p.enabled(),
                p.keySet(),
                ProviderService.isLocal(p.baseUrl()),
                p.createdAt(),
                last);
    }

    private static ModelView view(AiModel m) {
        return new ModelView(m.id(), m.providerId(), m.modelIdentifier(), m.capabilities(), m.enabled());
    }
}
