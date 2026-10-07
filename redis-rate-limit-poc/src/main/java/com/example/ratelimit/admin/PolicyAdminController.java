package com.example.ratelimit.admin;

import java.time.Instant;
import java.util.List;

import com.example.ratelimit.policy.ExemptionDocument;
import com.example.ratelimit.policy.ManagedPolicyStore;
import com.example.ratelimit.policy.ManagedPolicyStore.PolicyNotFoundException;
import com.example.ratelimit.policy.ManagedPolicyStore.PolicyStoreException;
import com.example.ratelimit.policy.ManagedPolicyStore.PolicyStoreUnavailableException;
import com.example.ratelimit.policy.PolicyConflictException;
import com.example.ratelimit.policy.PolicyDocument;
import com.example.ratelimit.policy.PolicyValidationException;
import com.example.ratelimit.policy.PolicySeeder;
import com.example.ratelimit.admin.AdminDtos.EnabledRequest;
import com.example.ratelimit.admin.AdminDtos.ErrorResponse;
import com.example.ratelimit.admin.AdminDtos.PolicyRequest;
import com.example.ratelimit.admin.AdminDtos.PolicyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration API for rate-limit policies.
 *
 * <p>Authorization is enforced in {@code SecurityConfig}: every path under {@code /api/admin/**}
 * requires {@code ROLE_ADMIN}. The demo {@code alice}/{@code bob} accounts hold {@code ROLE_USER} and
 * are refused here. This controller does not check roles itself, so there is one place to audit.
 *
 * <p>Mutations use {@link Authentication#getName()} as the audit actor. That is a principal name, never
 * a credential, and it is the only identity detail recorded.
 */
@RestController
@RequestMapping("/api/admin/rate-limit")
public class PolicyAdminController {

    private static final Logger log = LoggerFactory.getLogger(PolicyAdminController.class);

    private final ManagedPolicyStore store;
    private final PolicySeeder seeder;
    private final com.example.ratelimit.policy.ExemptionStore exemptions;

    public PolicyAdminController(ManagedPolicyStore store, PolicySeeder seeder,
            com.example.ratelimit.policy.ExemptionStore exemptions) {
        this.store = store;
        this.seeder = seeder;
        this.exemptions = exemptions;
    }

    @GetMapping("/policies")
    public List<PolicyResponse> list() {
        return store.findAll().stream().map(PolicyResponse::from).toList();
    }

    /**
     * What this build can actually enforce. The admin UI binds its algorithm dropdown and scope
     * selectors to this response, so an option is selectable only when the backend enforces it.
     * An enum constant elsewhere in the codebase is not enforcement.
     */
    @GetMapping("/capabilities")
    public CapabilitiesResponse capabilities() {
        return new CapabilitiesResponse(
                List.of(
                        new AlgorithmCapability("FIXED_WINDOW", true,
                                "Counter per epoch-aligned window. Preserves the original POC semantics.",
                                List.of(
                                        new Parameter("limit", true, "Requests allowed per window."),
                                        new Parameter("window", true,
                                                "Window length, ISO-8601 duration of at least 1s."))),
                        new AlgorithmCapability("SLIDING_WINDOW", true,
                                "Exact rolling window: at most limit events in any trailing window. State is one "
                                        + "sorted set per identity, trimmed on every decision and expired when idle; "
                                        + "memory is bounded by limit entries per identity.",
                                List.of(
                                        new Parameter("limit", true, "Events allowed per trailing window."),
                                        new Parameter("window", true,
                                                "Trailing window length, ISO-8601 duration of at least 1s."))),
                        new AlgorithmCapability("SLIDING_WINDOW_COUNTER", true,
                                "APPROXIMATE rolling window: current count plus the previous window count weighted "
                                        + "by how far the current window has advanced. Cheap, but an estimate, not a "
                                        + "guarantee.",
                                List.of(
                                        new Parameter("limit", true, "Approximate ceiling per window."),
                                        new Parameter("window", true,
                                                "Window length, ISO-8601 duration of at least 1s."))),
                        new AlgorithmCapability("TOKEN_BUCKET", true,
                                "Burst of capacity, then a sustained rate of capacity per refillInterval. "
                                        + "Capacity 100 with a 10s refill sustains 10/sec after the burst; "
                                        + "it does not mean 100/min. Cost defaults to 1.",
                                List.of(
                                        new Parameter("capacity", true, "Burst size in tokens."),
                                        new Parameter("refillInterval", true,
                                                "Time to refill an empty bucket, ISO-8601 duration of at least 1s."),
                                        new Parameter("cost", false,
                                                "Tokens per request, at least 1. Defaults to 1."))),
                        new AlgorithmCapability("LEAKY_BUCKET", true,
                                "POLICING, not queued shaping: requests beyond queueCapacity are rejected, never "
                                        + "queued. The water level drains continuously at drainRate requests per "
                                        + "second; Retry-After reflects when enough capacity is expected.",
                                List.of(
                                        new Parameter("drainRate", true, "Requests drained per second."),
                                        new Parameter("queueCapacity", true, "Burst depth before overflow rejects."))),
                        new AlgorithmCapability("CONCURRENCY_LIMIT", true,
                                "Caps in-flight requests across all instances with Redis leases. Permits release when "
                                        + "the request completes; crashed holders are reclaimed when the lease "
                                        + "expires. leaseDuration is the maximum request duration: a request running "
                                        + "longer may lose its permit.",
                                List.of(
                                        new Parameter("maxConcurrent", true, "Permits shared across instances."),
                                        new Parameter("leaseDuration", true,
                                                "Maximum request duration, ISO-8601 duration of at least 1s.")))),
                List.of(
                        new ScopeCapability("ENDPOINT", true, "Method plus route template."),
                        new ScopeCapability("IP", true, "Canonical client IP behind trusted-proxy gating."),
                        new ScopeCapability("USER", true,
                                "Authenticated principal; falls back to IP when unauthenticated. A USER policy on a "
                                        + "wide route pattern shares one quota across endpoints."),
                        new ScopeCapability("GLOBAL", true,
                                "One quota shared by every route and identity on every instance."),
                        new ScopeCapability("APPLICATION", true,
                                "One quota shared across all in-scope API routes, regardless of IP or user.")),
                "AND: every applicable enabled policy must allow. One atomic batch inspects all "
                        + "counters before charging any, so a denial charges nothing anywhere.",
                "single-redis: batch scripts span keys without hash tags; Redis Cluster is unsupported.");
    }

    public record AlgorithmCapability(String name, boolean implemented, String note,
            List<Parameter> parameters) {
    }

    public record Parameter(String name, boolean required, String help) {
    }

    public record ScopeCapability(String name, boolean implemented, String note) {
    }

    public record CapabilitiesResponse(
            List<AlgorithmCapability> algorithms,
            List<ScopeCapability> scopes,
            String composition,
            String topology) {
    }

    @GetMapping("/policies/{id}")
    public PolicyResponse get(@PathVariable String id) {
        return PolicyResponse.from(store.find(id).orElseThrow(() -> new PolicyNotFoundException(id)));
    }

    @PostMapping("/policies")
    public ResponseEntity<PolicyResponse> create(@RequestBody PolicyRequest request, Authentication auth) {
        PolicyDocument document = toDocument(request, null, actor(auth));
        PolicyDocument saved = store.save(document, null, actor(auth));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(etag(saved.version()))
                .body(PolicyResponse.from(saved));
    }

    @PutMapping("/policies/{id}")
    public ResponseEntity<PolicyResponse> update(@PathVariable String id, @RequestBody PolicyRequest request,
            Authentication auth) {
        PolicyDocument existing = store.find(id).orElseThrow(() -> new PolicyNotFoundException(id));
        if (request.id() != null && !id.equals(request.id())) {
            throw new PolicyValidationException(List.of(
                    "id in the body (" + request.id() + ") does not match the path (" + id + ")"));
        }
        if (request.version() == null) {
            throw new PolicyValidationException(List.of(
                    "version is required on update; send the version you read"));
        }
        PolicyDocument document = toDocument(request, existing, actor(auth));
        PolicyDocument saved = store.save(document, existing, actor(auth));
        return ResponseEntity.ok().eTag(etag(saved.version())).body(PolicyResponse.from(saved));
    }

    @PatchMapping("/policies/{id}/enabled")
    public ResponseEntity<PolicyResponse> setEnabled(@PathVariable String id,
            @RequestBody EnabledRequest request, Authentication auth) {
        PolicyDocument existing = store.find(id).orElseThrow(() -> new PolicyNotFoundException(id));
        if (request.enabled() == null) {
            throw new PolicyValidationException(List.of("enabled is required and must be true or false"));
        }
        // Same convention as PUT: the client sends the version it read, the store moves it to base + 1.
        long nextVersion = request.version() != null ? request.version() + 1 : existing.version() + 1;
        PolicyDocument updated = PolicyDocument.builder(existing.id())
                .name(existing.name())
                .route(existing.method(), existing.path())
                .algorithm(existing.algorithm())
                .scope(existing.scope())
                .window(existing.window(), existing.limit())
                .bucket(existing.capacity(), existing.refillInterval(), existing.cost())
                .leaky(existing.drainRate(), existing.queueCapacity())
                .concurrency(existing.maxConcurrent(), existing.leaseDuration())
                .enabled(request.enabled())
                .onRedisError(existing.onRedisError())
                .version(nextVersion)
                .timestamps(existing.createdAt(), Instant.now())
                .updatedBy(actor(auth))
                .build();
        PolicyDocument saved = store.save(updated, existing, actor(auth));
        return ResponseEntity.ok().eTag(etag(saved.version())).body(PolicyResponse.from(saved));
    }

    @DeleteMapping("/policies/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, Authentication auth) {
        PolicyDocument existing = store.find(id).orElseThrow(() -> new PolicyNotFoundException(id));
        store.delete(id, actor(auth), existing.version());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    public List<AuditEntryResponse> audit(@RequestParam(defaultValue = "50") int limit) {
        return store.audit(limit).stream()
                .map(e -> new AuditEntryResponse(e.at(), e.actor(), e.policyId(), e.operation(),
                        e.resultingVersion(), e.changedFields()))
                .toList();
    }

    /**
     * Intentional local reset: clears policies, audit and the seeded marker so the next start reseeds
     * from {@code application.yml}. Guarded by ROLE_ADMIN like every other mutation, and never called
     * automatically.
     */
    @PostMapping("/policies/reset")
    public ResponseEntity<ResetResponse> reset(Authentication auth) {
        int removed = store.reset(actor(auth));
        int reseeded = seeder.seed(actor(auth));
        return ResponseEntity.ok(new ResetResponse(removed, reseeded, Instant.now()));
    }

    public record AuditEntryResponse(Instant at, String actor, String policyId, String operation,
            long resultingVersion, List<String> changedFields) {
    }

    public record ResetResponse(int removed, int reseeded, Instant at) {
    }

    /**
     * Projects the request onto a document.
     *
     * <p>On create, {@code version} starts at 1 and {@code existing} is null. Omitted fields therefore
     * stay null rather than being read from {@code existing}: {@link PolicyDocument#validate()} then
     * reports exactly what is missing, instead of the save failing with a null dereference.
     *
     * <p>On update the client's version is carried through so the store's atomic compare-and-set is what
     * actually detects a concurrent edit.
     */
    private static PolicyDocument toDocument(PolicyRequest request, PolicyDocument existing, String actor) {
        Instant now = Instant.now();
        var b = PolicyDocument.builder(pick(request.id(), existing == null ? null : existing.id()))
                .name(pick(request.name(), existing == null ? null : existing.name()))
                .route(pick(request.method(), existing == null ? null : existing.method()),
                        pick(request.path(), existing == null ? null : existing.path()))
                .algorithm(pick(request.algorithm(), existing == null ? null : existing.algorithm()))
                .scope(pick(request.scope(), existing == null ? null : existing.scope()))
                .window(pick(request.window(), existing == null ? null : existing.window()),
                        pick(request.limit(), existing == null ? null : existing.limit()))
                .bucket(pick(request.capacity(), existing == null ? null : existing.capacity()),
                        pick(request.refillInterval(), existing == null ? null : existing.refillInterval()),
                        pick(request.cost(), existing == null ? null : existing.cost()))
                .leaky(pick(request.drainRate(), existing == null ? null : existing.drainRate()),
                        pick(request.queueCapacity(), existing == null ? null : existing.queueCapacity()))
                .concurrency(pick(request.maxConcurrent(), existing == null ? null : existing.maxConcurrent()),
                        pick(request.leaseDuration(), existing == null ? null : existing.leaseDuration()))
                .onRedisError(pick(request.onRedisError(), existing == null ? null : existing.onRedisError()))
                // The request carries the version the client read; the stored version becomes base + 1.
                // The store's Lua then requires stored == base, which is the optimistic-concurrency check.
                .version(request.version() != null ? request.version() + 1 : 1)
                .timestamps(existing != null ? existing.createdAt() : now, now)
                .updatedBy(actor);

        if (request.enabled() != null) {
            b.enabled(request.enabled());
        } else if (existing != null) {
            b.enabled(existing.enabled());
        }
        if (request.name() == null && existing != null) {
            b.name(existing.name());
        }
        return b.build();
    }

    /** Request value when supplied, otherwise the stored value, otherwise null. */
    private static <T> T pick(T requested, T stored) {
        return requested != null ? requested : stored;
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }

    /** Principal name only. No credential, no API key, no header value. */
    private static String actor(Authentication auth) {
        return auth == null ? "unknown" : auth.getName();
    }

    // --- error mapping. Each handler returns a safe message; nothing leaks internals to the client.

    @ExceptionHandler(PolicyValidationException.class)
    public ResponseEntity<ErrorResponse> onInvalid(PolicyValidationException e) {
        return ResponseEntity.badRequest().body(
                ErrorResponse.of("policy_invalid", "policy is invalid", e.problems()));
    }

    @ExceptionHandler(PolicyConflictException.class)
    public ResponseEntity<ErrorResponse> onConflict(PolicyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of("version_conflict",
                "stored version is " + e.storedVersion() + "; reload the policy and re-apply your change"));
    }

    @ExceptionHandler(PolicyNotFoundException.class)
    public ResponseEntity<ErrorResponse> onMissing(PolicyNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("policy_not_found", e.getMessage()));
    }

    @ExceptionHandler(PolicyStoreUnavailableException.class)
    public ResponseEntity<ErrorResponse> onUnavailable(PolicyStoreUnavailableException e) {
        log.warn("policy store unavailable while serving an admin request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse.of(
                "store_unavailable", "the policy store is temporarily unavailable"));
    }

    @ExceptionHandler(PolicyStoreException.class)
    public ResponseEntity<ErrorResponse> onStoreFailure(PolicyStoreException e) {
        log.error("policy store failure: {}", e.getMessage());
return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("store_error", "the policy store could not complete the request"));
    }

    // --- exemptions ---

    @GetMapping("/exemptions")
    public List<ExemptionResponse> listExemptions() {
        return exemptions.findAll().stream().map(ExemptionResponse::from).toList();
    }

    @PostMapping("/exemptions")
    public ResponseEntity<ExemptionResponse> createExemption(@RequestBody ExemptionRequest request, Authentication auth) {
        var doc = ExemptionDocument.builder(request.id())
                .name(request.name() != null ? request.name() : request.id())
                .route(request.method(), request.path())
                .enabled(true)
                .version(1)
                .updatedBy(actor(auth))
                .build();
        var saved = exemptions.save(doc, null, actor(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(ExemptionResponse.from(saved));
    }

    @DeleteMapping("/exemptions/{id}")
    public ResponseEntity<Void> deleteExemption(@PathVariable String id, Authentication auth) {
        exemptions.delete(id, actor(auth));
        return ResponseEntity.noContent().build();
    }

    public record ExemptionRequest(String id, String name, String method, String path) {
    }

    public record ExemptionResponse(String id, String name, String method, String path, boolean enabled,
            long version, Instant createdAt, Instant updatedAt, String updatedBy) {
        static ExemptionResponse from(com.example.ratelimit.policy.ExemptionDocument d) {
            return new ExemptionResponse(d.id(), d.name(), d.method(), d.path(), d.enabled(), d.version(),
                    d.createdAt(), d.updatedAt(), d.updatedBy());
        }
    }
}
