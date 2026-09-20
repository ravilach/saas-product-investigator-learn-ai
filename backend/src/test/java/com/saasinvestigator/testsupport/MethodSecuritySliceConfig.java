package com.saasinvestigator.testsupport;

import com.saasinvestigator.security.JwtService;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Makes {@code @PreAuthorize} actually enforce inside a {@code @WebMvcTest} slice.
 *
 * <p>Import this from any controller slice test that asserts on roles:
 *
 * <pre>{@code
 * @WebMvcTest(SomeController.class)
 * @Import({MethodSecuritySliceConfig.class, GlobalExceptionHandler.class})
 * }</pre>
 *
 * <p>Three things have to be true for a role assertion in a slice to mean anything, and none of them is true by
 * default:
 *
 * <ul>
 *   <li>{@link EnableMethodSecurity} must be present. Without it every {@code @PreAuthorize} is inert and the
 *       tests pass while enforcing nothing - the worst possible outcome for a security test.
 *   <li>{@link EnableWebSecurity} must be present to supply the {@link HttpSecurity} builder. The MVC slice pulls
 *       in no security auto-configuration of its own, so the chain below has nothing to build from without it.
 *   <li>The chain must permit everything at the URL level, so that a 403 can only have come from the annotation
 *       under test rather than from a path rule that happens to agree with it.
 * </ul>
 */
@TestConfiguration
@EnableMethodSecurity
@EnableWebSecurity
public class MethodSecuritySliceConfig {

    /**
     * A permit-all chain with CSRF disabled, matching the real configuration's bearer-token posture.
     *
     * @param http the builder supplied by {@link EnableWebSecurity}
     * @return the test chain
     * @throws Exception if the builder fails
     */
    @Bean
    SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .anonymous(Customizer.withDefaults())
                .build();
    }

    /**
     * Satisfies {@code JwtAuthenticationFilter}, which a {@code @WebMvcTest} slice instantiates because it is a
     * {@code Filter} bean, while excluding the {@code @Service} it depends on.
     *
     * <p>A mock rather than the real thing because no test using this config presents a token - each request is
     * authenticated directly with {@code TestPrincipals}, so the filter runs and finds nothing to do either way.
     * Declared here rather than as a {@code @MockitoBean} in every slice test so that adding a controller test does
     * not mean rediscovering why the context fails to load.
     *
     * @return a mock JWT service
     */
    @Bean
    JwtService jwtService() {
        return Mockito.mock(JwtService.class);
    }
}
