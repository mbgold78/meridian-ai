package com.meridian.substrate.engine.contract;

import com.meridian.substrate.common.domain.Contract;

/**
 * No-op {@link ContractAccountabilityMirror}. Since 3b-2 the
 * {@code ContractEngineConfig} default is the REAL
 * {@link DecisionLogContractAccountabilityMirror} (the pinned hash-vector
 * gate is satisfied — ADR D14 / port-map trap #3); this class remains as an
 * explicit opt-out bean and a test seam.
 */
public class NoopContractAccountabilityMirror implements ContractAccountabilityMirror {

    @Override
    public void onActivated(Contract contract) {
        // deliberate no-op (opt-out/test seam)
    }

    @Override
    public void onDeclined(Contract contract) {
        // deliberate no-op (opt-out/test seam)
    }

    @Override
    public void onBreachWaived(Contract contract,
                               com.meridian.substrate.common.domain.ContractBreach breach) {
        // deliberate no-op (opt-out/test seam)
    }
}
