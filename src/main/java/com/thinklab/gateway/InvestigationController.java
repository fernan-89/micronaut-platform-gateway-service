package com.thinklab.gateway;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * The gateway's own investigation endpoint (ADR-027). Like the session endpoints it lives under {@code /gateway/v1}, outside every
 * BIAN domain, because it is not proxied. See {@link PseudonymInvestigation} for the rules.
 */
@Controller("/gateway/v1/investigation")
public class InvestigationController {

    private final PseudonymInvestigation investigation;

    public InvestigationController(PseudonymInvestigation investigation) {
        this.investigation = investigation;
    }

    /** What a person is recorded as on the ledger: the pseudonym their sign-ins used, plus how to read the ledger by it. */
    @Post("/pseudonym")
    public Mono<Map<String, Object>> pseudonym(@Header("X-Tenant-Id") @NotBlank String tenantId, @Header("X-Executor") @NotBlank String executor,
                                               @Header("X-Role") @Nullable String role, @Body @Valid PseudonymLookupRequest request) {
        return Mono.defer(() -> investigation.lookup(tenantId, executor, role, request.email(), request.reason()));
    }

    @Serdeable
    public record PseudonymLookupRequest(
            @NotBlank(message = "email is required") @Size(max = 254, message = "email is too long") String email,
            @NotBlank(message = "reason is required") @Size(min = 10, max = 200, message = "reason must be between 10 and 200 characters") String reason) {
    }
}
