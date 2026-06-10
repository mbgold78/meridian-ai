package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.api.v1.request.ReaderRegistrationCreateRequest;
import com.meridian.substrate.app.core.service.ProbeService;
import com.meridian.substrate.app.core.service.ReaderRegistrationService;
import com.meridian.substrate.common.domain.ReaderRegistration;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/readers")
public class ReaderRegistrationController {

    private final ReaderRegistrationService readerService;

    public ReaderRegistrationController(ReaderRegistrationService readerService) {
        this.readerService = readerService;
    }

    @GetMapping
    public List<ReaderRegistration> listByDomain(@RequestParam UUID domainId) {
        return readerService.listByDomain(domainId);
    }

    @GetMapping("/{id}")
    public ReaderRegistration get(@PathVariable UUID id) {
        return readerService.get(id);
    }

    @PostMapping
    public ResponseEntity<ReaderRegistration> create(@Valid @RequestBody ReaderRegistrationCreateRequest req) {
        ReaderRegistration saved = readerService.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        readerService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Helper endpoint: probe a Structured reader's URI. Returns the probe
     * result without persisting. Phase 5 will integrate this into the full
     * reader-read flow.
     */
    @PostMapping("/{id}/probe")
    public ProbeService.ProbeResult probe(@PathVariable UUID id) {
        ReaderRegistration r = readerService.get(id);
        return readerService.probe(r);
    }
}
