/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.membership.messaging.rbc;

import java.util.concurrent.Executor;

import com.salesforce.apollo.crypto.DigestAlgorithm;
import com.salesforce.apollo.membership.Context;
import com.salesforce.apollo.membership.Member;
import com.salesforce.apollo.membership.SigningMember;
import com.salesforce.apollo.membership.messaging.MessagingMetrics;

public class Parameters {
    public static class Builder {
        private int              bufferSize        = 500;
        private Context<Member>  context;
        private DigestAlgorithm  digestAlgorithm   = DigestAlgorithm.DEFAULT;
        private Executor         executor;
        private double           falsePositiveRate = 0.125;
        private int              maxMessages       = 100;
        private SigningMember    member;
        private MessagingMetrics metrics;

        public Parameters build() {
            return new Parameters(bufferSize, maxMessages, context, digestAlgorithm, executor, member, metrics,
                                  falsePositiveRate);
        }

        public int getBufferSize() {
            return bufferSize;
        }

        public Context<Member> getContext() {
            return context;
        }

        public DigestAlgorithm getDigestAlgorithm() {
            return digestAlgorithm;
        }

        public Executor getExecutor() {
            return executor;
        }

        public double getFalsePositiveRate() {
            return falsePositiveRate;
        }

        public int getMaxMessages() {
            return maxMessages;
        }

        public SigningMember getMember() {
            return member;
        }

        public MessagingMetrics getMetrics() {
            return metrics;
        }

        public Parameters.Builder setBufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
            return this;
        }

        public Parameters.Builder setContext(Context<Member> context) {
            this.context = context;
            return this;
        }

        public Parameters.Builder setDigestAlgorithm(DigestAlgorithm digestAlgorithm) {
            this.digestAlgorithm = digestAlgorithm;
            return this;
        }

        public Builder setExecutor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public Builder setFalsePositiveRate(double falsePositiveRate) {
            this.falsePositiveRate = falsePositiveRate;
            return this;
        }

        public Builder setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
            return this;
        }

        public Parameters.Builder setMember(SigningMember member) {
            this.member = member;
            return this;
        }

        public Parameters.Builder setMetrics(MessagingMetrics metrics) {
            this.metrics = metrics;
            return this;
        }
    }

    public static Parameters.Builder newBuilder() {
        return new Builder();
    }

    public final int              bufferSize;
    public final Context<Member>  context;
    public final DigestAlgorithm  digestAlgorithm;
    public final Executor         executor;
    public final double           falsePositiveRate;
    public final int              maxMessages;
    public final SigningMember    member;
    public final MessagingMetrics metrics;

    public Parameters(int bufferSize, int maxMessages, Context<Member> context, DigestAlgorithm digestAlgorithm,
                      Executor executor, SigningMember member, MessagingMetrics metrics, double falsePositiveRate) {
        this.bufferSize = bufferSize;
        this.context = context;
        this.digestAlgorithm = digestAlgorithm;
        this.executor = executor;
        this.member = member;
        this.metrics = metrics;
        this.falsePositiveRate = falsePositiveRate;
        this.maxMessages = maxMessages;
    }
}
