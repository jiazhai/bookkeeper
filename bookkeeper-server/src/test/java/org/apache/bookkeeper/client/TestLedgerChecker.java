/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.bookkeeper.client;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.apache.bookkeeper.net.BookieSocketAddress;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.GenericCallback;
import org.apache.bookkeeper.test.BookKeeperClusterTestCase;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the functionality of LedgerChecker. This Ledger checker should be able
 * to detect the correct underReplicated fragment
 */
public class TestLedgerChecker extends BookKeeperClusterTestCase {
    private static final byte[] TEST_LEDGER_ENTRY_DATA = "TestCheckerData"
            .getBytes();
    private static final byte[] TEST_LEDGER_PASSWORD = "testpasswd".getBytes();
    static Logger LOG = LoggerFactory.getLogger(TestLedgerChecker.class);

    public TestLedgerChecker() {
        super(3);
    }

    class CheckerCallback implements GenericCallback<Set<LedgerFragment>> {
        private Set<LedgerFragment> result = null;
        private CountDownLatch latch = new CountDownLatch(1);

        public void operationComplete(int rc, Set<LedgerFragment> result) {
            this.result = result;
            latch.countDown();
        }

        Set<LedgerFragment> waitAndGetResult() throws InterruptedException {
            latch.await();
            return result;
        }
    }

    /**
     * Tests that the LedgerChecker should detect the underReplicated fragments
     * on multiple Bookie crashes
     */
    @Test(timeout = 60000)
    public void testChecker() throws Exception {

        LedgerHandle lh = bkc.createLedger(BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);
        startNewBookie();

        for (int i = 0; i < 10; i++) {
            lh.addEntry(TEST_LEDGER_ENTRY_DATA);
        }
        BookieSocketAddress replicaToKill = lh.getLedgerMetadata().getEnsembles()
                .get(0L).get(0);
        LOG.info("Killing {}", replicaToKill);
        killBookie(replicaToKill);

        for (int i = 0; i < 10; i++) {
            lh.addEntry(TEST_LEDGER_ENTRY_DATA);
        }

        Set<LedgerFragment> result = getUnderReplicatedFragments(lh);
        assertNotNull("Result shouldn't be null", result);
        for (LedgerFragment r : result) {
            LOG.info("unreplicated fragment: {}", r);
        }
        assertEquals("Should have one missing fragment", 1, result.size());
        assertEquals("There should be 1 fragments. But returned fragments are "
                + result, 1, result.size());
        LedgerFragment lf = result.iterator().next();
        assertEquals("There should be 1 failed bookies in first fragment " + lf,
                1, lf.getBookiesIndexes().size());
        assertEquals("Fragment should be missing from first replica",
                lf.getAddress(0), replicaToKill);

        BookieSocketAddress replicaToKill2 = lh.getLedgerMetadata()
                .getEnsembles().get(0L).get(1);
        LOG.info("Killing {}", replicaToKill2);
        killBookie(replicaToKill2);

        result = getUnderReplicatedFragments(lh);
        assertNotNull("Result shouldn't be null", result);
        for (LedgerFragment r : result) {
            LOG.info("unreplicated fragment: {}", r);
        }
        assertEquals("Should have two missing fragments", 2, result.size());
        for (LedgerFragment fragment : result) {
            if (fragment.getFirstEntryId() == 0L) {
                assertEquals("There should be 2 failed bookies in first fragment",
                        2, fragment.getBookiesIndexes().size());
            } else {
                assertEquals("There should be 1 failed bookies in second fragment",
                        1, fragment.getBookiesIndexes().size());
            }
        }
    }

    /**
     * Tests that ledger checker should pick the fragment as bad only if any of
     * the fragment entries not meeting the quorum.
     */
    // /////////////////////////////////////////////////////
    // /////////Ensemble = 3, Quorum = 2 ///////////////////
    // /Sample Ledger meta data should look like////////////
    // /0 a b c /////*entry present in a,b. Now kill c//////
    // /1 a b d ////////////////////////////////////////////
    // /Here even though one BK failed at this stage, //////
    // /we don't have any missed entries. Quorum satisfied//
    // /So, there should not be any missing replicas.///////
    // /////////////////////////////////////////////////////
    @Test(timeout = 3000)
    public void testShouldNotGetTheFragmentIfThereIsNoMissedEntry()
            throws Exception {

        LedgerHandle lh = bkc.createLedger(3, 2, BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);
        lh.addEntry(TEST_LEDGER_ENTRY_DATA);

        // Entry should have added in first 2 Bookies.

        // Kill the 3rd BK from ensemble.
        ArrayList<BookieSocketAddress> firstEnsemble = lh.getLedgerMetadata()
                .getEnsembles().get(0L);
        BookieSocketAddress lastBookieFromEnsemble = firstEnsemble.get(2);
        LOG.info("Killing " + lastBookieFromEnsemble + " from ensemble="
                + firstEnsemble);
        killBookie(lastBookieFromEnsemble);

        startNewBookie();

        LOG.info("Ensembles after first entry :"
                + lh.getLedgerMetadata().getEnsembles());

        // Adding one more entry. Here enseble should be reformed.
        lh.addEntry(TEST_LEDGER_ENTRY_DATA);

        LOG.info("Ensembles after second entry :"
                + lh.getLedgerMetadata().getEnsembles());

        Set<LedgerFragment> result = getUnderReplicatedFragments(lh);

        assertNotNull("Result shouldn't be null", result);

        for (LedgerFragment r : result) {
            LOG.info("unreplicated fragment: {}", r);
        }

        assertEquals("Should not have any missing fragment", 0, result.size());
    }

    /**
     * Tests that LedgerChecker should give two fragments when 2 bookies failed
     * in same ensemble when ensemble = 3, quorum = 2
     */
    @Test(timeout = 3000)
    public void testShouldGetTwoFrgamentsIfTwoBookiesFailedInSameEnsemble()
            throws Exception {

        LedgerHandle lh = bkc.createLedger(3, 2, BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);
        startNewBookie();
        startNewBookie();
        lh.addEntry(TEST_LEDGER_ENTRY_DATA);

        ArrayList<BookieSocketAddress> firstEnsemble = lh.getLedgerMetadata()
                .getEnsembles().get(0L);

        BookieSocketAddress firstBookieFromEnsemble = firstEnsemble.get(0);
        killBookie(firstEnsemble, firstBookieFromEnsemble);

        BookieSocketAddress secondBookieFromEnsemble = firstEnsemble.get(1);
        killBookie(firstEnsemble, secondBookieFromEnsemble);
        lh.addEntry(TEST_LEDGER_ENTRY_DATA);
        Set<LedgerFragment> result = getUnderReplicatedFragments(lh);

        assertNotNull("Result shouldn't be null", result);

        for (LedgerFragment r : result) {
            LOG.info("unreplicated fragment: {}", r);
        }

        assertEquals("There should be 1 fragments", 1, result.size());
        assertEquals("There should be 2 failed bookies in the fragment",
                2, result.iterator().next().getBookiesIndexes().size());
    }

    /**
     * Tests that LedgerChecker should not get any underReplicated fragments, if
     * corresponding ledger does not exists.
     */
    @Test(timeout = 3000)
    public void testShouldNotGetAnyFragmentIfNoLedgerPresent()
            throws Exception {

        LedgerHandle lh = bkc.createLedger(3, 2, BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);

        ArrayList<BookieSocketAddress> firstEnsemble = lh.getLedgerMetadata()
                .getEnsembles().get(0L);
        BookieSocketAddress firstBookieFromEnsemble = firstEnsemble.get(0);
        killBookie(firstBookieFromEnsemble);
        startNewBookie();
        lh.addEntry(TEST_LEDGER_ENTRY_DATA);
        bkc.deleteLedger(lh.getId());

        Set<LedgerFragment> result = getUnderReplicatedFragments(lh);
        assertNotNull("Result shouldn't be null", result);

        assertEquals("There should be 0 fragments. But returned fragments are "
                + result, 0, result.size());
    }

    /**
     * Tests that LedgerChecker should get failed ensemble number of fragments
     * if ensemble bookie failures on next entry
     */
    @Test(timeout = 3000)
    public void testShouldGetFailedEnsembleNumberOfFgmntsIfEnsembleBookiesFailedOnNextWrite()
            throws Exception {

        startNewBookie();
        startNewBookie();
        LedgerHandle lh = bkc.createLedger(3, 2, BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);
        for (int i = 0; i < 3; i++) {
            lh.addEntry(TEST_LEDGER_ENTRY_DATA);
        }

        // Kill all three bookies
        ArrayList<BookieSocketAddress> firstEnsemble = lh.getLedgerMetadata()
                .getEnsembles().get(0L);
        for (BookieSocketAddress bkAddr : firstEnsemble) {
            killBookie(firstEnsemble, bkAddr);
        }

        Set<LedgerFragment> result = getUnderReplicatedFragments(lh);

        assertNotNull("Result shouldn't be null", result);

        for (LedgerFragment r : result) {
            LOG.info("unreplicated fragment: {}", r);
        }

        assertEquals("There should be 1 fragments", 1, result.size());
        assertEquals("There should be 3 failed bookies in the fragment",
                3, result.iterator().next().getBookiesIndexes().size());
    }

    /**
     * Tests that LedgerChecker should not get any fragments as underReplicated
     * if Ledger itself is empty
     */
    @Test(timeout = 3000)
    public void testShouldNotGetAnyFragmentWithEmptyLedger() throws Exception {
        LedgerHandle lh = bkc.createLedger(3, 2, BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);
        Set<LedgerFragment> result = getUnderReplicatedFragments(lh);
        assertNotNull("Result shouldn't be null", result);
        assertEquals("There should be 0 fragments. But returned fragments are "
                + result, 0, result.size());
    }

    /**
     * Tests that LedgerChecker should get all fragments if ledger is empty
     * but all bookies in the ensemble are down.
     * In this case, there's no way to tell whether data was written or not.
     * In this case, there'll only be two fragments, as quorum is 2 and we only
     * suspect that the first entry of the ledger could exist.
     */
    @Test(timeout = 3000)
    public void testShouldGet2FragmentsWithEmptyLedgerButBookiesDead() throws Exception {
        LedgerHandle lh = bkc.createLedger(3, 2, BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);
        for (BookieSocketAddress b : lh.getLedgerMetadata().getEnsembles().get(0L)) {
            killBookie(b);
        }
        Set<LedgerFragment> result = getUnderReplicatedFragments(lh);
        assertNotNull("Result shouldn't be null", result);
        assertEquals("There should be 1 fragments.", 1, result.size());
        assertEquals("There should be 2 failed bookies in the fragment",
                2, result.iterator().next().getBookiesIndexes().size());
    }

    /**
     * Tests that LedgerChecker should one fragment as underReplicated
     * if there is an open ledger with single entry written.
     */
    @Test(timeout = 3000)
    public void testShouldGetOneFragmentWithSingleEntryOpenedLedger() throws Exception {
        LedgerHandle lh = bkc.createLedger(3, 3, BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);
        lh.addEntry(TEST_LEDGER_ENTRY_DATA);
        ArrayList<BookieSocketAddress> firstEnsemble = lh.getLedgerMetadata()
                .getEnsembles().get(0L);
        BookieSocketAddress lastBookieFromEnsemble = firstEnsemble.get(0);
        LOG.info("Killing " + lastBookieFromEnsemble + " from ensemble="
                + firstEnsemble);
        killBookie(lastBookieFromEnsemble);

        startNewBookie();

        //Open ledger separately for Ledger checker.
        LedgerHandle lh1 =bkc.openLedgerNoRecovery(lh.getId(), BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);

        Set<LedgerFragment> result = getUnderReplicatedFragments(lh1);
        assertNotNull("Result shouldn't be null", result);
        assertEquals("There should be 1 fragment. But returned fragments are "
                + result, 1, result.size());
        assertEquals("There should be 1 failed bookies in the fragment",
                1, result.iterator().next().getBookiesIndexes().size());
    }

    /**
     * Tests that LedgerChecker correctly identifies missing fragments
     * when a single entry is written after an ensemble change.
     * This is important, as the last add confirmed may be less than the
     * first entry id of the final segment.
     */
    @Test(timeout = 3000)
    public void testSingleEntryAfterEnsembleChange() throws Exception {
        LedgerHandle lh = bkc.createLedger(3, 3, BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);
        for (int i = 0; i < 10; i++) {
            lh.addEntry(TEST_LEDGER_ENTRY_DATA);
        }
        ArrayList<BookieSocketAddress> firstEnsemble = lh.getLedgerMetadata()
                .getEnsembles().get(0L);
        BookieSocketAddress lastBookieFromEnsemble = firstEnsemble.get(
                lh.getDistributionSchedule().getWriteSet(lh.getLastAddPushed()).get(0));
        LOG.info("Killing " + lastBookieFromEnsemble + " from ensemble="
                + firstEnsemble);
        killBookie(lastBookieFromEnsemble);
        startNewBookie();

        lh.addEntry(TEST_LEDGER_ENTRY_DATA);

        lastBookieFromEnsemble = firstEnsemble.get(
                lh.getDistributionSchedule().getWriteSet(lh.getLastAddPushed()).get(1));
        LOG.info("Killing " + lastBookieFromEnsemble + " from ensemble="
                + firstEnsemble);
        killBookie(lastBookieFromEnsemble);

        //Open ledger separately for Ledger checker.
        LedgerHandle lh1 =bkc.openLedgerNoRecovery(lh.getId(), BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);

        Set<LedgerFragment> result = getUnderReplicatedFragments(lh1);
        assertNotNull("Result shouldn't be null", result);
        assertEquals("There should be 2 fragments. But returned fragments are "
                + result, 2, result.size());
        for (LedgerFragment lf : result) {
            if (lf.getFirstEntryId() == 0L) {
                assertEquals("There should be 2 failed bookies in first fragment",
                        2, lf.getBookiesIndexes().size());
            } else {
                assertEquals("There should be 1 failed bookie in second fragment",
                        1, lf.getBookiesIndexes().size());
            }
        }
    }

    /**
     * Tests that LedgerChecker should return the fragment
     * from a closed ledger with 0 entries.
     */
    @Test(timeout = 3000)
    public void testClosedEmptyLedger() throws Exception {
        LedgerHandle lh = bkc.createLedger(3, 3, BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);
        ArrayList<BookieSocketAddress> firstEnsemble = lh.getLedgerMetadata()
                .getEnsembles().get(0L);
        lh.close();

        BookieSocketAddress lastBookieFromEnsemble = firstEnsemble.get(0);
        LOG.info("Killing " + lastBookieFromEnsemble + " from ensemble="
                + firstEnsemble);
        killBookie(lastBookieFromEnsemble);

        //Open ledger separately for Ledger checker.
        LedgerHandle lh1 =bkc.openLedgerNoRecovery(lh.getId(), BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);

        Set<LedgerFragment> result = getUnderReplicatedFragments(lh1);
        assertNotNull("Result shouldn't be null", result);
        assertEquals("There should be 0 fragment. But returned fragments are "
                + result, 1, result.size());
    }

    /**
     * Tests that LedgerChecker does not return any fragments
     * from a closed ledger with 0 entries.
     */
    @Test(timeout = 3000)
    public void testClosedSingleEntryLedger() throws Exception {
        LedgerHandle lh = bkc.createLedger(3, 2, BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);
        ArrayList<BookieSocketAddress> firstEnsemble = lh.getLedgerMetadata()
            .getEnsembles().get(0L);
        lh.addEntry(TEST_LEDGER_ENTRY_DATA);
        lh.close();

        // kill bookie 2
        BookieSocketAddress lastBookieFromEnsemble = firstEnsemble.get(2);
        LOG.info("Killing " + lastBookieFromEnsemble + " from ensemble="
                + firstEnsemble);
        killBookie(lastBookieFromEnsemble);

        //Open ledger separately for Ledger checker.
        LedgerHandle lh1 =bkc.openLedgerNoRecovery(lh.getId(), BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);

        Set<LedgerFragment> result = getUnderReplicatedFragments(lh1);
        assertNotNull("Result shouldn't be null", result);
        assertEquals("There should be 0 fragment. But returned fragments are "
                + result, 0, result.size());
        lh1.close();

        // kill bookie 1
        lastBookieFromEnsemble = firstEnsemble.get(1);
        LOG.info("Killing " + lastBookieFromEnsemble + " from ensemble="
                + firstEnsemble);
        killBookie(lastBookieFromEnsemble);
        startNewBookie();

        //Open ledger separately for Ledger checker.
        lh1 =bkc.openLedgerNoRecovery(lh.getId(), BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);

        result = getUnderReplicatedFragments(lh1);
        assertNotNull("Result shouldn't be null", result);
        assertEquals("There should be 1 fragment. But returned fragments are "
                + result, 1, result.size());
        assertEquals("There should be 1 failed bookies in the fragment",
                1, result.iterator().next().getBookiesIndexes().size());
        lh1.close();

        // kill bookie 0
        lastBookieFromEnsemble = firstEnsemble.get(0);
        LOG.info("Killing " + lastBookieFromEnsemble + " from ensemble="
                + firstEnsemble);
        killBookie(lastBookieFromEnsemble);
        startNewBookie();

        //Open ledger separately for Ledger checker.
        lh1 =bkc.openLedgerNoRecovery(lh.getId(), BookKeeper.DigestType.CRC32,
                TEST_LEDGER_PASSWORD);

        result = getUnderReplicatedFragments(lh1);
        assertNotNull("Result shouldn't be null", result);
        assertEquals("There should be 1 fragment. But returned fragments are "
                + result, 1, result.size());
        assertEquals("There should be 2 failed bookies in the fragment",
                2, result.iterator().next().getBookiesIndexes().size());
        lh1.close();
    }

    private Set<LedgerFragment> getUnderReplicatedFragments(LedgerHandle lh)
            throws InterruptedException {
        LedgerChecker checker = new LedgerChecker(bkc);
        CheckerCallback cb = new CheckerCallback();
        checker.checkLedger(lh, cb);
        return cb.waitAndGetResult();
    }

    private void killBookie(ArrayList<BookieSocketAddress> firstEnsemble,
            BookieSocketAddress ensemble) throws InterruptedException {
        LOG.info("Killing " + ensemble + " from ensemble=" + firstEnsemble);
        killBookie(ensemble);
    }

}
