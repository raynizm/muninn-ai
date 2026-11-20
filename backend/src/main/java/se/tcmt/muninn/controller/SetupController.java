package se.tcmt.muninn.controller;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.service.SetupService;

@RestController
@RequestMapping("/api/setup")
@RequiredArgsConstructor
public class SetupController {

    private final SetupService setup;

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("complete", setup.isComplete());
    }

    public record TokenRequest(String token) {}

    @PostMapping("/verify")
    public Map<String, Boolean> verify(@RequestBody TokenRequest request) {
        requireIncomplete();
        if (!setup.verifyToken(request.token())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid setup token");
        }
        return Map.of("valid", true);
    }

    /** Every setup endpoint except status must call this first. */
    private void requireIncomplete() {
        if (setup.isComplete()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
