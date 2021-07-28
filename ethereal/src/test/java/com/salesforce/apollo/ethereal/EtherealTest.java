/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.ethereal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.salesfoce.apollo.ethereal.proto.ByteMessage;
import com.salesfoce.apollo.ethereal.proto.PreUnit_s;
import com.salesforce.apollo.comm.LocalRouter;
import com.salesforce.apollo.comm.ServerConnectionCache;
import com.salesforce.apollo.crypto.DigestAlgorithm;
import com.salesforce.apollo.ethereal.Data.PreBlock;
import com.salesforce.apollo.ethereal.Ethereal.Controller;
import com.salesforce.apollo.ethereal.PreUnit.preUnit;
import com.salesforce.apollo.membership.Context;
import com.salesforce.apollo.membership.Member;
import com.salesforce.apollo.membership.SigningMember;
import com.salesforce.apollo.membership.impl.SigningMemberImpl;
import com.salesforce.apollo.membership.messaging.rbc.ReliableBroadcaster;
import com.salesforce.apollo.membership.messaging.rbc.ReliableBroadcaster.Parameters;
import com.salesforce.apollo.utils.ChannelConsumer;
import com.salesforce.apollo.utils.Utils;

/**
 * @author hal.hildebrand
 *
 */
public class EtherealTest {

    static PreUnit newPreUnit(long id, Crown crown, Any data, byte[] rsData, DigestAlgorithm algo) {
        var t = PreUnit.decode(id);
        if (t.height() != crown.heights()[t.creator()] + 1) {
            throw new IllegalStateException("Inconsistent height information in preUnit id and crown");
        }
        return new preUnit(t.creator(), t.epoch(), t.height(), PreUnit.computeHash(algo, id, crown, data, rsData),
                           crown, data, rsData);
    }

    private static class SimpleDataSource implements DataSource {
        final Deque<Any> dataStack = new ArrayDeque<>();

        @Override
        public Any getData() {
            return dataStack.pollFirst();
        }

    }

    @Test
    public void assembled() {
        Controller controller;
        short nProc = 4;

        var config = Config.deterministic().setCanSkipLevel(false).setExecutor(ForkJoinPool.commonPool())
                           .setnProc(nProc).setNumberOfEpochs(2).build();
        DataSource ds = new SimpleDataSource();
        ChannelConsumer<PreUnit> synchronizer = new ChannelConsumer<>(new LinkedBlockingDeque<>(100));

        List<PreUnit> syncd = new ArrayList<>();
        synchronizer.consumeEach(pu -> syncd.add(pu));

        Ethereal e = new Ethereal();
        controller = e.deterministic(config, ds, i -> {
        }, pu -> synchronizer.getChannel().offer(pu));
        try {
            controller.start();
            assertNotNull(controller.input());

            for (short pid = 1; pid < config.nProc(); pid++) {
                var crown = Crown.emptyCrown(nProc, DigestAlgorithm.DEFAULT);
                var unitData = Any.getDefaultInstance();
                var rsData = new byte[0];
                long id = PreUnit.id(0, pid, 0);
                var pu = newPreUnit(id, crown, unitData, rsData, DigestAlgorithm.DEFAULT);
                controller.input().accept(config.pid(), Collections.singletonList(pu));
            }
            Utils.waitForCondition(2_000, () -> syncd.size() >= 2);

            assertEquals(2, syncd.size());

            PreUnit pu = syncd.get(0);
            assertEquals(0, pu.creator());
            assertEquals(0, pu.epoch());
            assertEquals(0, pu.height());

            pu = syncd.get(1);
            assertEquals(0, pu.creator());
            assertEquals(0, pu.epoch());
            assertEquals(1, pu.height());
        } finally {
            controller.stop();
        }
    }

    @Test
    public void fourWay() throws Exception {
        short nProc = 4;
        ChannelConsumer<PreUnit> synchronizer = new ChannelConsumer<>(new LinkedBlockingDeque<>());

        List<Ethereal> ethereals = new ArrayList<>();
        List<DataSource> dataSources = new ArrayList<>();
        List<Controller> controllers = new ArrayList<>();
        var builder = Config.deterministic().setExecutor(ForkJoinPool.commonPool()).setnProc(nProc);

        List<List<PreBlock>> produced = new ArrayList<>();
        for (int i = 0; i < nProc; i++) {
            produced.add(new ArrayList<>());
        }

        for (short i = 0; i < nProc; i++) {
            var e = new Ethereal();
            var ds = new SimpleDataSource();
            var out = new ChannelConsumer<>(new LinkedBlockingDeque<PreBlock>(100));
            List<PreBlock> output = produced.get(i);
            out.consume(l -> output.addAll(l));
            var controller = e.deterministic(builder.setPid(i).build(), ds, pb -> out.getChannel().offer(pb),
                                             pu -> synchronizer.getChannel().offer(pu));
            ethereals.add(e);
            dataSources.add(ds);
            controllers.add(controller);
            for (int d = 0; d < 500; d++) {
                ds.dataStack.add(Any.pack(ByteMessage.newBuilder()
                                                     .setContents(ByteString.copyFromUtf8("pid: " + i + " data: " + d))
                                                     .build()));
            }
        }

        synchronizer.consume(pu -> {
            for (short i = 0; i < controllers.size(); i++) {
                var controller = controllers.get(i);
                short pid = i;
                pu.forEach(p -> {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e1) {
                        return;
                    }
                    if (pid == p.creator()) {
                    } else {
                        controller.input().accept((short) 0, Collections.singletonList(p));
                    }
                });
            }
            controllers.forEach(e -> {
            });
        });
        try {
            controllers.forEach(e -> e.start());

            Utils.waitForCondition(15_000, 100, () -> {
                for (var pb : produced) {
                    if (pb.size() < 87) {
                        return false;
                    }
                }
                return true;
            });
        } finally {
            controllers.forEach(e -> e.stop());
        }
        for (int i = 0; i < nProc; i++) {
            assertEquals(87, produced.get(i).size(), "Failed to receive all preblocks on process: " + i);
        }
        List<PreBlock> preblocks = produced.get(0);
        List<String> outputOrder = new ArrayList<>();

        for (int i = 1; i < nProc; i++) {
            for (int j = 0; j < preblocks.size(); j++) {
                var a = preblocks.get(j);
                var b = produced.get(i).get(j);
                assertEquals(a.data().size(), b.data().size());
                for (int k = 0; k < a.data().size(); k++) {
                    assertEquals(a.data().get(k), b.data().get(k));
                    outputOrder.add(new String(a.data().get(k).unpack(ByteMessage.class).getContents().toByteArray()));
                }
                assertEquals(a.randomBytes(), b.randomBytes());
            }
        }
    }

    @Test
    public void rbc() throws Exception {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);
        short nProc = 50;
        SigningMember[] members = new SigningMember[nProc];
        Context<Member> context = new Context<>(DigestAlgorithm.DEFAULT.getOrigin().prefix(1), 0.33, nProc);
        Map<SigningMember, ReliableBroadcaster> casting = new HashMap<>();
        List<LocalRouter> comms = new ArrayList<>();
        Parameters.Builder params = Parameters.newBuilder().setBufferSize(1500).setContext(context);
        for (int i = 0; i < nProc; i++) {
            SigningMember member = new SigningMemberImpl(Utils.getMember(i));
            context.activate(member);
            members[i] = member;
        }

        for (int i = 0; i < nProc; i++) {
            var member = members[i];
            LocalRouter router = new LocalRouter(member, ServerConnectionCache.newBuilder(), ForkJoinPool.commonPool());
            comms.add(router);
            casting.put(member, new ReliableBroadcaster(params.setMember(member).build(), router));
            router.start();
        }

        List<Ethereal> ethereals = new ArrayList<>();
        List<DataSource> dataSources = new ArrayList<>();
        List<Controller> controllers = new ArrayList<>();
        var builder = Config.deterministic().setExecutor(ForkJoinPool.commonPool()).setnProc(nProc);

        List<List<PreBlock>> produced = new ArrayList<>();
        for (int i = 0; i < nProc; i++) {
            produced.add(new ArrayList<>());
        }

        for (short i = 0; i < nProc; i++) {
            var e = new Ethereal();
            var ds = new SimpleDataSource();
            var out = new ChannelConsumer<PreBlock>(new LinkedBlockingDeque<>(100));
            List<PreBlock> output = produced.get(i);
            out.consume(l -> output.addAll(l));
            ReliableBroadcaster caster = casting.get(members[i]);
            var controller = e.deterministic(builder.setPid(i).build(), ds, pb -> out.getChannel().offer(pb), pu -> {
                caster.publish(pu.toPreUnit_s().toByteArray());
//                System.out.println("Broadcasting: "+ pu + " on: " + caster.getMember());
            });
            ethereals.add(e);
            dataSources.add(ds);
            controllers.add(controller);
            for (int d = 0; d < 2500; d++) {
                ds.dataStack.add(Any.pack(ByteMessage.newBuilder()
                                                     .setContents(ByteString.copyFromUtf8("pid: " + i + " data: " + d))
                                                     .build()));
            }
        }
        try {
            for (int i = 0; i < nProc; i++) {
                var controller = controllers.get(i);
                var caster = casting.get(members[i]);
                caster.registerHandler((ctx, msgs) -> msgs.forEach(msg -> {
                    preUnit pu;
                    try {
                        pu = PreUnit.from(PreUnit_s.parseFrom(msg.content()), DigestAlgorithm.DEFAULT);
                    } catch (InvalidProtocolBufferException e) {
                        throw new IllegalStateException(e);
                    }
                    controller.input().accept(pu.creator(), Collections.singletonList(pu));
//                    System.out.println("Input: "+ pu + " on: " + caster.getMember());
                }));
                caster.start(Duration.ofMillis(5), scheduler);
            }
            controllers.forEach(e -> e.start());

            Utils.waitForCondition(60_000, 100, () -> {
                for (var pb : produced) {
                    if (pb.size() < 87) {
                        return false;
                    }
                }
                return true;
            });
        } finally {
            controllers.forEach(e -> e.stop());
        }
        for (int i = 0; i < nProc; i++) {
            assertEquals(87, produced.get(i).size(), "Failed to receive all preblocks on process: " + i);
        }
        List<PreBlock> preblocks = produced.get(0);
        List<String> outputOrder = new ArrayList<>();

        for (int i = 1; i < nProc; i++) {
            for (int j = 0; j < preblocks.size(); j++) {
                var a = preblocks.get(j);
                var b = produced.get(i).get(j);
                assertEquals(a.data().size(), b.data().size());
                for (int k = 0; k < a.data().size(); k++) {
                    assertEquals(a.data().get(k), b.data().get(k));
                    outputOrder.add(new String(a.data().get(k).unpack(ByteMessage.class).getContents().toByteArray()));
                }
                assertEquals(a.randomBytes(), b.randomBytes());
            }
        }
    }
}
