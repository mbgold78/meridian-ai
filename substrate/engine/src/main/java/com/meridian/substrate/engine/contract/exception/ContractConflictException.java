package com.meridian.substrate.engine.contract.exception;

import java.util.UUID;

/**
 * 409-conflict family for contract commands (engine-spec contracts.md §4.5
 * "other 409 codes"; ADR-002 D7/D13). The app-module
 * {@code GlobalExceptionHandler} maps this to a 409 envelope carrying the
 * spec code.
 *
 * <p>None of these codes is decline vocabulary: DECLINE is a legitimate
 * first-class governance outcome that returns HTTP <b>200</b> always
 * (port-map trap #8; ADR D6) — conflicts arise only from frozen content,
 * post-terminal commands, or conflicting party responses. The fourth §4.5
 * code, {@code CONTRACT_DENIAL}, belongs to the 3c gateway
 * ({@code ContractDeniedException}) and is deliberately not part of this
 * family.
 */
public class ContractConflictException extends RuntimeException {

    /** Envelope {@code errors[].code} values (spec §4.5). */
    public enum Code {
        /**
         * ADR D7 content freeze: contract content is mutable only while
         * {@code status=PROPOSED} AND zero party responses exist.
         */
        CONTRACT_CONTENT_FROZEN,
        /**
         * State-machine guard (spec §2.2): terminal contracts reject further
         * mutating actions; PROPOSED contracts reject {@code invoke_decline}
         * (proposal-stage declines go through respond). A state error — never
         * a "decline error": decline itself returns 200.
         */
        INVALID_CONTRACT_STATE,
        /**
         * Conflicting re-response for a party that already responded
         * differently (§10.2 — duplicate IDENTICAL responses are an
         * idempotent 200 instead, never this code).
         */
        RESPONSE_CONFLICT
    }

    private final Code code;
    private final UUID contractId;

    public ContractConflictException(Code code, UUID contractId, String message) {
        super(message);
        this.code = code;
        this.contractId = contractId;
    }

    public Code getCode() {
        return code;
    }

    public UUID getContractId() {
        return contractId;
    }
}
