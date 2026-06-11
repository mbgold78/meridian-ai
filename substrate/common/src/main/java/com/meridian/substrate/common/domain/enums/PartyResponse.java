package com.meridian.substrate.common.domain.enums;

/**
 * A party's recorded response to a PROPOSED contract (engine-spec
 * contracts.md §2.1). DECLINED is a legitimate response, never an error —
 * the respond endpoint returns 200 on the decline path (spec §7.2).
 */
public enum PartyResponse {
    ACCEPTED,
    DECLINED
}
