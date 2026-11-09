package fr.devradar;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final String[] origins;
    public WebConfig(@Value("${devradar.allowed-origins}") String origins) { this.origins = origins.split(","); }
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origins).allowedMethods("GET");
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            private final Cache<String, AtomicInteger> requests = Caffeine.newBuilder()
                    .maximumSize(10000).expireAfterWrite(Duration.ofMinutes(1)).build();
            @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
                if (req.getMethod().equals("OPTIONS")) return true;
                if (requests.get(req.getRemoteAddr(), ip -> new AtomicInteger()).incrementAndGet() <= 20) return true;
                res.setStatus(429);
                res.setHeader("Retry-After", "60");
                res.setContentType("application/problem+json;charset=UTF-8");
                res.getWriter().write("{\"status\":429,\"title\":\"Trop de requêtes\",\"detail\":\"Réessayez dans une minute.\"}");
                return false;
            }
        }).addPathPatterns("/api/**");
    }
}
