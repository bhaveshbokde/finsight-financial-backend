package com.finsight.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI document metadata and the bearer-token security scheme.
 *
 * <p>Registering {@code bearerAuth} here is what makes Swagger UI's "Authorize"
 * button appear and attach {@code Authorization: Bearer <token>} to try-it-out
 * requests. Controllers opt in with
 * {@code @SecurityRequirement(name = "bearerAuth")}; the auth endpoints omit it,
 * which is how the generated document records that they are public.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI finsightOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FinSight API")
                        .version("1.0.0")
                        .description("""
                                Financial transaction management and cash-flow analytics.

                                **Authentication.** Call `POST /api/v1/auth/register` or
                                `POST /api/v1/auth/login`, then send the returned `accessToken`
                                as `Authorization: Bearer <token>` on every other endpoint.

                                **Money.** All amounts are decimal with 2 displayed fraction
                                digits (stored with 4). `amount` is always positive; direction
                                comes from `type` (INCOME / EXPENSE).

                                **Dates.** `txnDate` is a calendar date with no time zone.
                                Report ranges are inclusive on both ends. Defaults resolve in UTC.

                                **Errors.** Every failure returns the same `ApiError` body with a
                                stable `code` and a `traceId` to quote when reporting a problem.

                                All data in this project is synthetic. It connects to no real
                                financial institution.
                                """)
                        .contact(new Contact().name("FinSight"))
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local development")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the `accessToken` returned by /auth/login.")));
    }
}
