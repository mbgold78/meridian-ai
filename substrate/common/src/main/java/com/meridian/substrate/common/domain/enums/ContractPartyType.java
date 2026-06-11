package com.meridian.substrate.common.domain.enums;

/**
 * Type of a contract party (engine-spec contracts.md §2.1; [CONTRACT-CONTENT]).
 *
 * <p>{@code COMBINED} models a combined obligation across multiple
 * subsidiaries (fixture party "Sentari, Tellix, Bursar (combined obligation)").
 */
public enum ContractPartyType {
    DOMAIN,
    ORG_UNIT,
    COMBINED,
    EXTERNAL
}
