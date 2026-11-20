package se.tcmt.muninn.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the single-page web app: client-side routes are forwarded to {@code index.html} so deep
 * links and reloads work. Keep this list in sync with the routes in {@code web/src/routes.tsx}.
 */
@Controller
public class WebAppController {

  /** Forwards a client-side route to the SPA entry point. */
  @GetMapping({"/", "/login", "/login/callback", "/setup", "/settings", "/recordings/{id}"})
  public String index() {
    return "forward:/index.html";
  }
}
