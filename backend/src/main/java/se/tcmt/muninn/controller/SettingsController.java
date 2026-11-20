package se.tcmt.muninn.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.tcmt.muninn.service.SettingsService;
import se.tcmt.muninn.service.SettingsService.SettingsUpdate;
import se.tcmt.muninn.service.SettingsService.SettingsView;

/** ADMIN only (see {@code SecurityConfig}): anonymous gets 401, other roles 403. */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

  private final SettingsService settings;

  /** Returns the current system settings. */
  @GetMapping
  public SettingsView get() {
    return settings.get();
  }

  /** Updates the system settings and returns the result. */
  @PutMapping
  public SettingsView put(@RequestBody SettingsUpdate update) {
    return settings.update(update);
  }
}
