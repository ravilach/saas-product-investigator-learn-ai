package com.saasinvestigator.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The generated API description, and the one thing springdoc cannot infer.
 *
 * <h2>Why the security scheme has to be declared</h2>
 *
 * <p>springdoc reads controller annotations to work out paths, parameters and response shapes, but authentication is a
 * filter-level concern: nothing in a {@code @RestController} says "send a bearer token". Without the declaration below,
 * Swagger UI renders every endpoint with no way to authenticate, so trying any of them returns a 401 and the docs look
 * broken rather than protected.
 *
 * <p>The requirement is applied globally rather than per-operation because all but one endpoint needs it. The exception
 * is {@code POST /api/auth/login}, which is where the token comes from - it simply ignores an {@code Authorization}
 * header, so listing it as secured costs nothing and is one less annotation to forget on a future endpoint that does
 * need it.
 */
@Configuration
public class OpenApiConfig {

    /** The name the scheme is registered under; referenced by the global requirement below. */
    private static final String BEARER_SCHEME = "bearerAuth";

    /**
     * @return the API description, with JWT bearer authentication declared and required by default
     */
    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SaaS Product Investigator API")
                        .version("v1")
                        .description("""
                                Track SaaS products, run LLM-driven investigations of what changed since last time, \
                                and export the resulting reports.

                                Authenticate with `POST /api/auth/login`, then send the returned token as \
                                `Authorization: Bearer <token>`. Endpoints marked ADMIN require the `ADMIN` role; the \
                                rest need only a valid token.

                                Note that `/actuator/health` and `/actuator/prometheus` are outside this description \
                                and are intentionally unauthenticated - restrict them at the network layer. See \
                                `docs/DEPLOYMENT.md`.""")
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        // Declared so Swagger UI labels the field correctly; it has no effect on validation, which is
                        // entirely the security filter's business.
                        .bearerFormat("JWT")
                        .description("Paste the token from POST /api/auth/login. Do not include the 'Bearer ' prefix.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
