/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.stereotomy.services.grpc.observer;

import java.util.List;

import com.salesfoce.apollo.stereotomy.event.proto.AttachmentEvent;
import com.salesfoce.apollo.stereotomy.event.proto.KERL_;
import com.salesfoce.apollo.stereotomy.event.proto.KeyEvent_;
import com.salesfoce.apollo.stereotomy.event.proto.Validations;
import com.salesforce.apollo.cryptography.Digest;

/**
 * @author hal.hildebrand
 *
 */
public interface EventObserver {

   void publish(KERL_ kerl, List<Validations> validations, Digest from);

    void publishAttachments(List<AttachmentEvent> attachments, Digest from);

   void publishEvents(List<KeyEvent_> events, List<Validations> validations, Digest from);
}
