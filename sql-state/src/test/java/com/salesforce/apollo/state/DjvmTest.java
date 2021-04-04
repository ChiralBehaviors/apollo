/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.state;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.util.Properties;
import java.util.function.Function;

import org.h2.api.Trigger;
import org.h2.jdbc.JdbcConnection;
import org.junit.jupiter.api.Test;

import com.salesforce.apollo.protocols.Utils;

import net.corda.djvm.TypedTaskFactory;
import net.corda.djvm.execution.ExecutionProfile;

/**
 * @author hal.hildebrand
 *
 */
public class DjvmTest {

    public interface WithJava {

        static <T, R> R run(TypedTaskFactory taskFactory, Class<? extends Function<T, R>> taskClass, T input) {
            try {
                return taskFactory.create(taskClass).apply(input);
            } catch (Exception e) {
                throw asRuntime(e);
            }
        }

        static RuntimeException asRuntime(Throwable t) {
            return (t instanceof RuntimeException) ? (RuntimeException) t : new RuntimeException(t.getMessage(), t);
        }
    }

    @Test
    public void smoke() throws Exception {
        File dir = File.createTempFile("foo", "bar");
        dir.delete();
        dir.deleteOnExit();
        try (Functions funcs = new Functions(Functions.defaultConfig(), ExecutionProfile.DEFAULT, dir)) {
            @SuppressWarnings("unchecked")
            Class<? extends Function<long[], Long>> clazz = (Class<? extends Function<long[], Long>>) funcs.compile("SimpleTask",
                                                                                                                    Utils.getDocument(getClass().getResourceAsStream("/SimpleTask.java")));
            funcs.execute(clazz);

            Trigger trigger = funcs.compileTrigger("TestTrigger",
                                                   Utils.getDocument(getClass().getResourceAsStream("/TestTrigger.java")));
            assertNotNull(trigger);

            try (java.sql.Connection db1 = new JdbcConnection("jdbc:h2:mem:djvm", new Properties())) {
                trigger.fire(db1, new Object[0], new Object[0]);
            }
        }
    }
}
