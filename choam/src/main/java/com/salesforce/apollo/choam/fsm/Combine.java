/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.choam.fsm;

import com.chiralbehaviors.tron.FsmExecutor;

/**
 * @author hal.hildebrand
 *
 */
public interface Combine {

    interface Transitions extends FsmExecutor<Combine, Combine.Transitions> {
        default Transitions fail() {
            return Merchantile.PROTOCOL_FAILURE;
        }

        default Transitions regenerate() {
            throw fsm().invalidTransitionOn();
        }

        default Transitions regenerated() {
            throw fsm().invalidTransitionOn();
        }

        default Transitions start() {
            throw fsm().invalidTransitionOn();
        }

        default Transitions synchronizationFailed() {
            throw fsm().invalidTransitionOn();
        }

        default Transitions synchronizing() {
            throw fsm().invalidTransitionOn();
        }
    }

    static final String AWAIT_SYNC = "AWAIT_SYNC";

    void awaitRegeneration();

    void awaitSynchronization();

    void regenerate();
    
    void cancelTimer(String timer);
}