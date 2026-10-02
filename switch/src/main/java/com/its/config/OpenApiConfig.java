package com.its.config;

import java.util.List;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import com.its.web.WebConfig;

/** API docs at /swagger-ui.html (spec at /v3/api-docs). */
@Configuration
public class OpenApiConfig {

    private static final String PARTICIPANT = "participant";
    private static final String API_KEY = "apiKey";
    private static final String ADMIN_KEY = "adminKey";

    @Bean
    OpenAPI switchApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Instant Transfer Switch")
                .version("0.2.0")
                .description("""
                    Real-time interbank transfers with ISO 20022 messages. Banks authenticate with `X-Participant` \
                    (their BIC) **and** `X-Api-Key`; operators close settlement with `X-Admin-Key`.

                    Demo keys: `ALFAMYKL` / `alfa-dev-key`, `BRVOMYKL` / `bravo-dev-key`, `CHRLMYKL` / `charlie-dev-key`; \
                    admin `local-admin-key`.""")
                .license(new License().name("MIT")))
            .components(new Components()
                .addSecuritySchemes(PARTICIPANT, header("X-Participant", "Your bank's BIC, e.g. ALFAMYKL"))
                .addSecuritySchemes(API_KEY, header("X-Api-Key", "Your bank's API key"))
                .addSecuritySchemes(ADMIN_KEY, header("X-Admin-Key", "Operator key for settlement actions")));
    }

    /** Marks each operation with the headers it needs, using the same path patterns as the interceptors in WebConfig. */
    @Bean
    OpenApiCustomizer securityByPath() {
        AntPathMatcher matcher = new AntPathMatcher();
        SecurityRequirement bank = new SecurityRequirement().addList(PARTICIPANT).addList(API_KEY); // both headers
        SecurityRequirement admin = new SecurityRequirement().addList(ADMIN_KEY);
        return api -> api.getPaths().forEach((path, item) -> {
            List<SecurityRequirement> security = WebConfig.ADMIN_PATHS.stream().anyMatch(p -> matcher.match(p, path)) ? List.of(admin)
                : WebConfig.PARTICIPANT_PATHS.stream().anyMatch(p -> matcher.match(p, path.replaceAll("\\{[^}]+}", "x"))) ? List.of(bank)
                : List.of();
            item.readOperations().forEach(op -> op.setSecurity(security));
        });
    }

    private static SecurityScheme header(String name, String description) {
        return new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name(name).description(description);
    }
}
