package se.tcmt.muninn.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import se.tcmt.muninn.service.SetupService;

/**
 * Once setup is complete every /api/setup endpoint answers 404 except {@code GET /status} and the recovery
 * {@code PUT /sso}. Runs before request-body binding, so a malformed body cannot reveal an endpoint either.
 * The service re-check the setup state; this is the outer layer.
 */
@Configuration
@RequiredArgsConstructor
class SetupCompleteGuard implements WebMvcConfigurer, HandlerInterceptor {

    private final SetupService setup;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/setup/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        boolean alwaysOpen = ("GET".equals(method) && path.equals("/api/setup/status"))
                || ("PUT".equals(method) && path.equals("/api/setup/sso"));
        if (!alwaysOpen && setup.isComplete()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return true;
    }
}
