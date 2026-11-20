package com.muninn.config;

import com.muninn.config.SettingsService.SettingsUpdate;
import com.muninn.config.SettingsService.SettingsView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN only (see {@code SecurityConfig}): anonymous gets 401, other roles 403. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public SettingsView get() {
        return settings.get();
    }

    @PutMapping
    public SettingsView put(@RequestBody SettingsUpdate update) {
        return settings.update(update);
    }
}
