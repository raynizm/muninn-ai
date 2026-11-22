package se.tcmt.muninn.controller;

import java.time.DateTimeException;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.service.StatsService;

/** Statistics over the signed-in user's recordings. */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

  private final StatsService stats;

  /** Totals and a per-month series; {@code tz} is an IANA time zone id and defaults to UTC. */
  @GetMapping
  public StatsService.Stats get(
      @AuthenticationPrincipal AppUser user, @RequestParam(defaultValue = "UTC") String tz) {
    ZoneId zone;
    try {
      zone = ZoneId.of(tz);
    } catch (DateTimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown time zone");
    }
    return stats.compute(user.id(), zone);
  }
}
