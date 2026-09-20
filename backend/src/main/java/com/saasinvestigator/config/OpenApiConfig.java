package com.saasinvestigator.config;

import com.saasinvestigator.error.ApiErrorResponse;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Arrays;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.method.HandlerMethod;

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
     * Tag shared by {@code AdminCredentialController} and {@code JwtSecretController}.
     *
     * <p>Both are referenced through these constants rather than each spelling the strings out, because an
     * {@code @Tag} is identified by name <em>and</em> description: two controllers claiming the same name with
     * different wording emit the tag twice in the document, and Swagger UI then renders two groups with the same
     * heading. Sharing one constant makes the annotations identical, so they collapse to a single group - which is
     * the intent, since these endpoints are the one "Secrets" screen in the Admin Console.
     */
    public static final String ADMIN_SECRETS_TAG = "Admin - Secrets";

    /** Description for {@link #ADMIN_SECRETS_TAG}; must be identical everywhere the tag is used. */
    public static final String ADMIN_SECRETS_TAG_DESCRIPTION =
            "System-wide LLM provider credentials and the JWT signing secret override";

    /** Schema name the error responses below point at, registered into components by {@link #openApi()}. */
    private static final String ERROR_SCHEMA = "ApiErrorResponse";

    /**
     * @return the API description, with JWT bearer authentication declared and required by default
     */
    @Bean
    public OpenAPI openApi() {
        Components components = new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                // Declared so Swagger UI labels the field correctly; it has no effect on validation, which is
                // entirely the security filter's business.
                .bearerFormat("JWT")
                .description("Paste the token from POST /api/auth/login. Do not include the 'Bearer ' prefix."));

        // ApiErrorResponse is never a controller return type - the global exception handler produces it - so
        // springdoc never encounters it while scanning signatures and would not register a schema for it. Resolving
        // it explicitly is what lets commonErrorResponses() below reference it by $ref instead of inlining a
        // hand-written copy of the record's fields that would drift the first time the record changes.
        ResolvedSchema error = ModelConverters.getInstance()
                .readAllAsResolvedSchema(new AnnotatedType(ApiErrorResponse.class));
        if (error != null && error.referencedSchemas != null) {
            error.referencedSchemas.forEach(components::addSchemas);
        }

        return new OpenAPI()
                .components(components)
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
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    /**
     * Documents the failure responses that are a property of the plumbing rather than of any one endpoint.
     *
     * <h2>Why this is a customizer and not {@code @ApiResponse} annotations</h2>
     *
     * <p>Without it every operation in Swagger UI advertises only its success code, which reads as though a request
     * either works or returns nothing describable - so the single {@link ApiErrorResponse} shape that the frontend's
     * one error renderer is built around appears nowhere in the published contract.
     *
     * <p>The alternative is four annotations on each of ~30 handler methods, all saying the same thing. That is the
     * kind of repetition that goes stale unevenly: the next endpoint added gets the ones its author remembered, and
     * the document slowly becomes a record of who copied what. Deriving them from the handler instead means a new
     * endpoint is documented correctly on the day it is written, without anyone remembering anything.
     *
     * <p>Only genuinely cross-cutting codes are added here, and each is inferred from something real rather than
     * applied blanket, because a documented response that cannot occur is as misleading as a missing one:
     *
     * <ul>
     *   <li><b>400</b> - only if the handler takes arguments at all. Something has to be malformed for a 400, and
     *       a no-argument endpoint gives the caller nothing to get wrong.</li>
     *   <li><b>401</b> - every operation, including {@code POST /api/auth/login}, which returns it for bad
     *       credentials. Everything else returns it for an absent, expired or unparseable token.</li>
     *   <li><b>403</b> - only where {@code @PreAuthorize} names a role. An {@code isAuthenticated()} check cannot
     *       produce one: an anonymous caller is rejected by the entry point as a 401 before authorization runs, so
     *       tagging those operations 403 would document a response no caller can ever receive.</li>
     *   <li><b>404</b> - only where a path variable exists, since that is the only way to name something absent.</li>
     * </ul>
     *
     * <p>Codes specific to one endpoint's behaviour - 409 on the uniqueness checks, 503 on capacity rejection and
     * failed exports - stay as explicit {@code @ApiResponse} annotations on the methods that can raise them, where the
     * reason is visible next to the code that causes it.
     *
     * <p>Existing entries are never overwritten, so a method-level annotation always wins over the default here.
     *
     * <h2>Two things a method-level {@code @ApiResponse} needs, neither of them obvious</h2>
     *
     * <p>Both of these were observed on {@code PUT /api/saas-products/{id}}, and both produce a wrong document rather
     * than an error, so nothing points them out:
     *
     * <ol>
     *   <li><b>Spell out the {@code content}</b>, every time, as
     *       {@code @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))}.
     *       A contentless {@code @ApiResponse} is not left empty for this customizer to fill - springdoc assigns the
     *       <em>handler's return type</em> to it, so a 409 gets documented as returning a {@code SaasProductResponse}.
     *       The explicit {@code mediaType} matters too: omitted, the entry lands under the wildcard media type while
     *       every response generated here is {@code application/json}, so one failure shape gets described two ways.</li>
     *   <li><b>Add {@code @ResponseStatus(HttpStatus.OK)}</b> if the handler does not already declare a status.
     *       springdoc only synthesises the success response for a status it can see: with any {@code @ApiResponse}
     *       present it stops inferring the implicit 200, so annotating an error response silently deletes the success
     *       case from the document. Handlers that already carry {@code @ResponseStatus} - 201, 202, 204 - are
     *       unaffected, which is why this only bites the 200 ones.</li>
     * </ol>
     *
     * @return a customizer applied to every generated operation
     */
    @Bean
    public OperationCustomizer commonErrorResponses() {
        return (operation, handlerMethod) -> {
            ApiResponses responses = operation.getResponses();

            if (handlerMethod.getMethod().getParameterCount() > 0) {
                addIfAbsent(responses, "400", "Malformed request: validation failed, or a parameter could not be parsed.");
            }
            addIfAbsent(responses, "401", "Missing, expired or invalid bearer token (or, on login, bad credentials).");
            if (requiresRole(handlerMethod)) {
                addIfAbsent(responses, "403", "Authenticated, but the caller's role does not permit this operation.");
            }
            if (hasPathVariable(handlerMethod)) {
                addIfAbsent(responses, "404", "No such resource, or it is not reachable under this path.");
            }
            return operation;
        };
    }

    /**
     * Adds one error response, leaving any existing entry for that code untouched.
     *
     * @param responses the operation's responses, mutated in place
     * @param code the HTTP status code
     * @param description what the caller should understand from it
     */
    private void addIfAbsent(ApiResponses responses, String code, String description) {
        if (responses.containsKey(code)) {
            return;
        }
        responses.addApiResponse(code, new ApiResponse().description(description).content(errorContent()));
    }

    /**
     * @return a fresh {@code application/json} content block pointing at the shared error schema. Built per call
     *     rather than shared, because each {@link ApiResponse} owns its content and serialisation is free to mutate it
     */
    private Content errorContent() {
        return new Content().addMediaType("application/json",
                new MediaType().schema(new Schema<>().$ref(ERROR_SCHEMA)));
    }

    /**
     * @param handlerMethod the handler being documented
     * @return whether authorization for it turns on a role, and can therefore fail with a 403
     */
    private boolean requiresRole(HandlerMethod handlerMethod) {
        // Checked on the method first and then the declaring class, mirroring how Spring Security resolves it: a
        // class-level @PreAuthorize applies to every method that does not override it (AuditLogController).
        PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class);
        PreAuthorize effective = onMethod != null
                ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), PreAuthorize.class);
        return effective != null && effective.value().contains("hasRole");
    }

    /**
     * @param handlerMethod the handler being documented
     * @return whether it names a resource by path, and can therefore fail with a 404
     */
    private boolean hasPathVariable(HandlerMethod handlerMethod) {
        return Arrays.stream(handlerMethod.getMethodParameters())
                .anyMatch(parameter -> parameter.hasParameterAnnotation(PathVariable.class));
    }
}
