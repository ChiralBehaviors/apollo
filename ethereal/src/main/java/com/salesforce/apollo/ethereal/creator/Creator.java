/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.ethereal.creator;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;
import com.salesforce.apollo.ethereal.Config;
import com.salesforce.apollo.ethereal.DataSource;
import com.salesforce.apollo.ethereal.Ethereal;
import com.salesforce.apollo.ethereal.PreUnit;
import com.salesforce.apollo.ethereal.Unit;
import com.salesforce.apollo.utils.Channel;

/**
 * Creator is a component responsible for producing new units. It processes
 * units produced by other committee members and stores the ones with the
 * highest level as possible parents (candidates). Whenever there are enough
 * parents to produce a unit on a new level, the creator creates a new Unit from
 * the available DataSource, signs and sends (using a function given to the
 * constructor) this new unit.
 * 
 * @author hal.hildebrand
 *
 */
public class Creator {
    @FunctionalInterface
    public interface RandomSourceData {
        byte[] apply(int level, List<Unit> parents, int epoch);
    }

    @FunctionalInterface
    public interface RsData {
        byte[] rsData(int level, Unit[] parents, int epoch);
    }

    private record built(Unit[] parents, int level) {}

    private static final Logger log = LoggerFactory.getLogger(Ethereal.class);

    /**
     * MakeConsistent ensures that the set of parents follows "parent consistency
     * rule". Modifies the provided unit slice in place. Parent consistency rule
     * means that unit's i-th parent cannot be lower (in a level sense) than i-th
     * parent of any other of that units parents. In other words, units seen from U
     * "directly" (as parents) cannot be below the ones seen "indirectly" (as
     * parents of parents).
     */
    private static void makeConsistent(Unit[] parents) {
        for (int i = 0; i < parents.length; i++) {
            for (int j = 0; j < parents.length; j++) {
                if (parents[j] == null) {
                    continue;
                }
                Unit u = parents[j].parents()[i];
                if (parents[i] == null || (u != null && u.level() > parents[i].level())) {
                    parents[i] = u;
                }
            }
        }
    }

    private final Unit[]                               candidates;
    private final Config                               conf;
    private final DataSource                           ds;
    private int                                        epoch;
    private boolean                                    epochDone;
    private EpochProofBuilder                          epochProof;
    private final Function<Integer, EpochProofBuilder> epochProofBuilder;
    private final Set<Short>                           frozen = new HashSet<>();
    private int                                        level;
    private int                                        maxLvl;
    private final Lock                                 mx     = new ReentrantLock();
    private short                                      onMaxLvl;
    private final int                                  quorum;
    private final RsData                               rsData;

    private final Consumer<Unit> send;

    public Creator(Config config, DataSource ds, Consumer<Unit> send, RsData rsData,
                   Function<Integer, EpochProofBuilder> epochProofBuilder) {
        this.conf = config;
        this.ds = ds;
        this.rsData = rsData;
        this.epochProofBuilder = epochProofBuilder;
        this.send = send;
        this.candidates = new Unit[config.nProc()];
        quorum = 2 * config.byzantine() + 1;
    }

    /**
     * Spawns the main loop of the creator. Units appearing on unitBelt are examined
     * and stored to be used as parents of future units. When there are enough new
     * parents, a new unit is produced. lastTiming is a channel on which the last
     * timing unit of each epoch is expected to appear. This method is stopped by
     * closing unitBelt channel.
     */
    public void createUnits(Channel<Unit> unitBelt, Queue<Unit> lastTiming) {
        newEpoch(epoch, ByteString.EMPTY);
        unitBelt.consume(units -> consume(units, lastTiming));
    }

    private built buildParents() {
        if (conf.canSkipLevel()) {
            return new built(getParents(), level);
        } else {
            var l = candidates[conf.pid()].level() + 1;
            return new built(getParentsForLevel(l), l);
        }
    }

    private void consume(List<Unit> units, Queue<Unit> lastTiming) {
        log.trace("Processing next units: {} on: {}", units.size(), conf.pid());
        mx.lock();
        try {
            for (Unit u : units) {
                // Step 1: update candidates with all units waiting on the unit belt
                update(u);
            }
            while (ready()) {
                log.trace("Ready, creating units on: {}", conf.pid());
                // Step 2: get parents and level using current strategy
                var built = buildParents();
                // Step 3: create unit
                createUnit(built.parents, built.level, getData(built.level, lastTiming));
            }
        } catch (Throwable e) {
            log.error("Error in processing units on: {}", conf.pid(), e);
        } finally {
            mx.unlock();
        }
    }

    private void createUnit(Unit[] parents, int level, ByteString data) {
        assert parents.length == conf.nProc();
        Unit u = PreUnit.newFreeUnit(conf.pid(), epoch, parents, level, data, rsData.rsData(level, parents, epoch),
                                     conf.digestAlgorithm());
        log.debug("Created unit: {} on: {}", u, conf.pid());
        send.accept(u);
        update(u);
    }

    /**
     * produces a piece of data to be included in a unit on a given level. For
     * regular units the provided DataSource is used. For finishing units it's
     * either nil or, if available, an encoded threshold signature share of hash and
     * id of the last timing unit (obtained from preblockMaker on lastTiming
     * channel)
     **/
    private ByteString getData(int level, Queue<Unit> lastTiming) {
        if (level <= conf.lastLevel()) {
            if (ds != null) {
                return ds.getData();
            }
            return ByteString.EMPTY;
        }
        Unit timingUnit = lastTiming.poll();
        if (timingUnit == null) {
            log.trace("No timing unit: {} on: {}", level, conf.pid());
            return ByteString.EMPTY;
        }
        // in a rare case there can be timing units from previous epochs left on
        // lastTiming channel. the purpose of this loop is to drain and ignore them.
        while (timingUnit != null) {
            if (timingUnit.epoch() == epoch) {
                epochDone = true;
                if (epoch == conf.numberOfEpochs() - 1) {
                    // the epoch we just finished is the last epoch we were supposed to produce
                    return ByteString.EMPTY;
                }
                log.debug("TimingUnit, new epoch required: {} on: {}", timingUnit, conf.pid());
                return epochProof.buildShare(timingUnit);
            }
            log.debug("Creator received timing unit from newer epoch: {} that previously encountered: {} on: {}",
                      timingUnit.epoch(), epoch, conf.pid());
            timingUnit = lastTiming.poll();
        }
        return ByteString.EMPTY;
    }

    private Unit[] getParents() {
        Unit[] result = Arrays.copyOf(candidates, candidates.length);
        makeConsistent(result);
        return result;
    }

    /**
     * getParentsForLevel returns a set of candidates such that their level is at
     * most level-1.
     */
    private Unit[] getParentsForLevel(int level) {
        var result = new Unit[conf.nProc()];
        for (int i = 0; i < candidates.length; i++) {
            Unit u = candidates[i];
            for (; u != null && u.level() >= level; u = u.predecessor())
                ;
            if (u != null) {
                result[u.creator()] = u;
            }
        }
        makeConsistent(result);
        return result;
    }

    /**
     * switches the creator to a chosen epoch, resets candidates and shares and
     * creates a dealing with the provided data.
     **/
    private void newEpoch(int epoch, ByteString data) {
        log.trace("Changing epoch to: {} on: {}", epoch, conf.pid());
        this.epoch = epoch;
        epochDone = false;
        resetEpoch();
        epochProof = epochProofBuilder.apply(epoch);
        createUnit(new Unit[conf.nProc()], 0, data);
    }

    /**
     * ready checks if the creator is ready to produce a new unit. Usually that
     * means: "do we have enough new candidates to produce a unit with level higher
     * than the previous one?" Besides that, we stop producing units for the current
     * epoch after creating a unit with signature share.
     */
    private boolean ready() {
        boolean ready = !epochDone && level > candidates[conf.pid()].level();
        log.trace("ready check: {} epoch done: {} : {} : {} on: {}", ready, epochDone, level,
                  candidates[conf.pid()].level(), conf.pid());
        return ready;
    }

    /**
     * resets the candidates and all related variables to the initial state (a slice
     * with NProc nils). This is useful when switching to a new epoch.
     */
    private void resetEpoch() {
        log.debug("Resetting epoch on: {}", conf.pid());
        Arrays.setAll(candidates, u -> null);
        maxLvl = -1;
        onMaxLvl = 0;
        level = 0;
    }

    /**
     * takes a unit and updates the receiver's state with information contained in
     * the unit.
     */
    private void update(Unit unit) {
        log.trace("updating: {} on: {}", unit, conf.pid());
        // if the unit is from an older epoch or unit's creator is known to be a forker,
        // we simply ignore it
        if (frozen.contains(unit.creator()) || unit.epoch() < epoch) {
            log.debug("Unit: {} rejected frozen: {} current: {} on: {}", unit, frozen.contains(unit.creator()), epoch,
                      conf.pid());
            return;
        }

        // If the unit is from a new epoch, switch to that epoch.
        // Since units appear on the belt in order they were added to the dag,
        // the first unit from a new epoch is always a dealing unit.
        if (unit.epoch() > epoch) {
            if (!epochProof.verify(unit)) {
                log.warn("Unit did not verify epoch, rejected on: {}", conf.pid());
                return;
            }
            newEpoch(unit.epoch(), unit.data());
        }

        // If this is a finishing unit try to extract threshold signature share from it.
        // If there are enough shares to produce the signature (and therefore a proof
        // that the current epoch is finished) switch to a new epoch.
        ByteString ep = epochProof.tryBuilding(unit);
        if (ep != null) {
            log.info("Advancing epoch to: {} using: {} on: {}", epoch + 1, unit, conf.pid());
            newEpoch(epoch + 1, ep);
            return;
        }
        log.trace("No epoch proof generated from: {} on: {}", unit, conf.pid());

        updateCandidates(unit);

    }

    /**
     * updateCandidates puts the provided unit in parent candidates provided that
     * the level is higher than the level of the previous candidate for that
     * creator.
     */
    private void updateCandidates(Unit u) {
        if (u.epoch() != epoch) {
            return;
        }
        var prev = candidates[u.creator()];
        if (prev == null || prev.level() < u.level()) {
            candidates[u.creator()] = u;
            log.trace("Update candidate to: {} on: {}", u, conf.pid());
            if (u.level() == maxLvl) {
                onMaxLvl++;
                log.trace("Update candidate onMaxLvl incremented to: {} by: {} on: {}", onMaxLvl, u, conf.pid());
            }
            if (u.level() > maxLvl) {
                maxLvl = u.level();
                onMaxLvl = 1;
                log.trace("Update candidate {} new maxLvl: {} on: {}", u, conf.pid());
            }
            level = maxLvl;
            log.trace("Update candidate new level: {} via: {} on: {}", level, u, conf.pid());
            if (onMaxLvl >= quorum) {
                level++;
                log.trace("Update candidate onMaxLvl: {} >= quorum: {} level now: {} via: {} on: {}", onMaxLvl, quorum,
                          level, u, conf.pid());
            }
        }
    }

}
