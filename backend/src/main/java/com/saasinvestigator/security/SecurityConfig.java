package com.saasinvestigator.security;

import com.saasinvestigator.config.SpaForwardingConfig;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The application's single security filter chain.
 *
 * <p>Authorisation is expressed in two layers on purpose. This class draws the coarse line - what is
 * reachable without a token at all - while {@code @PreAuthorize} on controller methods draws the
 * role-level line next to the code it protects, where it is visible to anyone reading the endpoint. Trying
 * to express every role rule as a URL pattern here would put the rules far from the handlers and go stale
 * the first time a path changed.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityErrorWriter securityErrorWriter;
    private final List<String> corsAllowedOrigins;

    /**
     * @param jwtAuthenticationFilter populates the security context from the bearer token
     * @param securityErrorWriter renders 401/403 as this project's JSON error shape
     * @param corsAllowedOrigins origins allowed to call the API cross-origin, from
     *     {@code app.cors.allowed-origins}
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          SecurityErrorWriter securityErrorWriter,
                          @Value("${app.cors.allowed-origins}") List<String> corsAllowedOrigins) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.securityErrorWriter = securityErrorWriter;
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    /**
     * BCrypt, with the library default work factor (10).
     *
     * <p>Chosen over a hand-picked cost so the value tracks the library's own recommendation rather than a
     * number frozen at the time this was written.
     *
     * @return the password encoder used for every hash and every comparison
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Builds the filter chain.
     *
     * @param http the Spring Security builder
     * @return the configured chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // No cookies are used for auth, so there is no cookie for a cross-site form to ride on and
                // nothing for CSRF tokens to protect. Leaving CSRF on would only break the bearer-token API.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Login has to be reachable without a token, by definition.
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()

                        // Actuator is intentionally unauthenticated: Prometheus scrapers do not
                        // authenticate, and an app-layer credential shared with every scraper is not a
                        // meaningful control. Restrict /actuator/** at the network or ingress layer
                        // instead - see /docs/DEPLOYMENT.md, which spells out what to block and why.
                        .requestMatchers("/actuator/**").permitAll()

                        // API documentation. Useful precisely when you have not logged in yet.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()

                        // The built frontend, served from the same jar in the packaged image. These are
                        // public files in a public bundle; the API behind them is what is protected.
                        //
                        // Matched by prefix rather than by listing routes, because the frontend's routes
                        // are the frontend's business: /login, /products/{id}, /admin/users and the rest
                        // exist only in the client router, and a browser following a bookmark or pressing
                        // reload sends an ordinary GET for one of them. Enumerating them here meant every
                        // route except "/" answered 401 with a JSON body in the packaged image - see
                        // SpaForwardingConfig, which resolves exactly this set to the HTML shell. The two
                        // share one predicate so they cannot disagree about which paths those are.
                        .requestMatchers(request -> HttpMethod.GET.matches(request.getMethod())
                                && SpaForwardingConfig.isFrontendPath(request.getRequestURI()))
                        .permitAll()

                        // Browser preflight carries no Authorization header and must not 401.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Everything else, including every /api/** endpoint not listed above, needs a
                        // valid token. Defaulting to authenticated means a newly added endpoint is
                        // protected by omission rather than exposed by it.
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(securityErrorWriter)
                        .accessDeniedHandler(securityErrorWriter))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(Customizer.withDefaults())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS rules for the API.
     *
     * <p>Needed only for local development, where Vite serves the frontend on its own port and every API
     * call is therefore cross-origin. In the packaged image the frontend is served from the same origin as
     * the API and none of this applies - which is why the allowed origins are configurable and default to
     * the Vite dev server rather than to a wildcard.
     *
     * @return the CORS configuration source
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsAllowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        // No credentials: auth travels in the Authorization header, not cookies, so allowing credentials
        // would loosen the policy without enabling anything the app actually does.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
