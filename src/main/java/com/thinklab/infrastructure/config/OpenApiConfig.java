package com.thinklab.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;

/**
 * Infrastructure Component: OpenAPI 3.0 Documentation Metadata.
 *
 * <p><b>Architectural Role:</b>
 * Centralizes the global API contract definition. During the Ahead-of-Time (AOT)
 * compilation phase, the 'micronaut-openapi' AST processor parses these annotations
 * to statically generate the official swagger.yml specification.
 *
 * @author Thinklab Core Infrastructure Team
 * @version 1.0.0-NASA-SRE-PROD
 * @since 1.0
 */
@OpenAPIDefinition(
        info = @Info(
                title = "Thinklab Platform Gateway Service Domain",
                version = "v1.0.0",
                description = "BIAN-aligned Service Domain (Control Record: Route) for the IT route inventory lifecycle (PROVISIONED, READY, DEPLOYED, MAINTENANCE, DECOMMISSIONED), tenant-scoped listing, holder/location assignment and an immutable forensic audit ledger. All routes follow the /platform-gateway/v1/{behavior-qualifier} convention (initiate, retrieve, update, assignment, control, audit-log). Illegal state transitions are reported as HTTP 422 with an RFC 7807 problem document.",
                contact = @Contact(
                        name = "Thinklab SRE & Security Operations",
                        email = "sre-core@thinklab.com",
                        url = "https://engineering.thinklab.com"
                ),
                license = @License(
                        name = "Proprietary & Confidential - Thinklab Internal Only",
                        url = "https://thinklab.com/security/compliance"
                )
        )
)
public class OpenApiConfig {
    // Empty class serving strictly as an AST metadata anchor for Swagger generation.
}
