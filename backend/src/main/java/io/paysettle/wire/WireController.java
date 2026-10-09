package io.paysettle.wire;

import io.paysettle.common.ApiException;
import io.paysettle.common.AuditLog;
import io.paysettle.security.Actor;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wire transfers. Both submission endpoints require an Idempotency-Key header (IETF draft
 * "The Idempotency-Key HTTP Header Field"): 201 for a new wire, 200 with the original wire for a retry.
 */
@RestController
public class WireController {

    private final WireService service;
    private final WireRepository repo;
    private final AuditLog audit;

    public WireController(WireService service, WireRepository repo, AuditLog audit) {
        this.service = service;
        this.repo = repo;
        this.audit = audit;
    }

    @PostMapping(value = "/api/v1/wires", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                      @Valid @RequestBody WireRequest request) {
        return respond(service.submitJson(key, request, Actor.current()));
    }

    /** Accepts an ISO 20022 pacs.008.001.08 document, e.g. forwarded from the mainframe or a channel. */
    @PostMapping(value = "/api/v1/wires/iso20022", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<Map<String, Object>> createIso(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                         @RequestBody String pacs008) {
        return respond(service.submitIso(key, pacs008, Actor.current()));
    }

    @GetMapping("/api/v1/wires")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return repo.list(status);
    }

    @GetMapping("/api/v1/wires/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        Map<String, Object> w = new LinkedHashMap<>(repo.view(id).orElseThrow(() -> ApiException.notFound("Wire not found")));
        w.put("history", audit.history(id));
        return w;
    }

    @GetMapping(value = "/api/v1/wires/{id}/pacs008", produces = MediaType.APPLICATION_XML_VALUE)
    public String pacs008(@PathVariable UUID id) {
        return (String) repo.view(id).orElseThrow(() -> ApiException.notFound("Wire not found")).get("pacs008");
    }

    /** Operator confirms a possible duplicate is intended (fraud and sanctions holds are released by analysts). */
    @PostMapping("/api/v1/wires/{id}/release")
    public Map<String, Object> release(@PathVariable UUID id) {
        service.releaseDuplicateHold(id, Actor.current());
        return get(id);
    }

    @PostMapping("/api/v1/wires/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id) {
        service.cancel(id, Actor.current());
        return get(id);
    }

    private ResponseEntity<Map<String, Object>> respond(WireService.Outcome o) {
        Map<String, Object> body = new LinkedHashMap<>(get(o.wireId()));
        body.remove("pacs008");
        body.remove("pacs002");
        body.put("idempotentReplay", o.replay());
        return ResponseEntity.status(o.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }
}
