/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.bookkeeper.client;

import org.apache.bookkeeper.client.BookKeeper.DigestType;
import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.bookkeeper.test.BookKeeperClusterTestCase;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TestReadLastConfirmedLongPoll extends BookKeeperClusterTestCase {
    final DigestType digestType;

    public TestReadLastConfirmedLongPoll() {
        super(6);
        this.digestType = DigestType.CRC32;
    }

    @Test(timeout = 60000)
    public void testReadLACLongPollWhenAllBookiesUp() throws Exception {
        final int numEntries = 3;

        final LedgerHandle lh = bkc.createLedger(3, 3, 1, digestType, "".getBytes());
        LedgerHandle readLh = bkc.openLedgerNoRecovery(lh.getId(), digestType, "".getBytes());
        assertEquals(LedgerHandle.INVALID_ENTRY_ID, readLh.getLastAddConfirmed());
        // add entries
        for (int i = 0; i < (numEntries - 1); i++) {
            lh.addEntry(("data" + i).getBytes());
        }
        final AtomicBoolean success = new AtomicBoolean(false);
        final AtomicInteger numCallbacks = new AtomicInteger(0);
        final CountDownLatch latch1 = new CountDownLatch(1);
        readLh.asyncTryReadLastConfirmed(new AsyncCallback.ReadLastConfirmedCallback() {
            @Override
            public void readLastConfirmedComplete(int rc, long lastConfirmed, Object ctx) {
                numCallbacks.incrementAndGet();
                if (BKException.Code.OK == rc) {
                    success.set(true);
                } else {
                    success.set(false);
                }
                latch1.countDown();
            }
        }, null);
        latch1.await();
        assertTrue(success.get());
        assertTrue(numCallbacks.get() == 1);
        assertEquals(numEntries - 3, readLh.getLastAddConfirmed());
        // try read last confirmed again
        success.set(false);
        numCallbacks.set(0);
        final CountDownLatch latch2 = new CountDownLatch(1);
        readLh.asyncReadLastConfirmedAndEntry(1000, true, new AsyncCallback.ReadLastConfirmedAndEntryCallback() {
            @Override
            public void readLastConfirmedAndEntryComplete(int rc, long lastConfirmed, LedgerEntry entry, Object ctx) {
                numCallbacks.incrementAndGet();
                if (BKException.Code.OK == rc && lastConfirmed == (numEntries - 2)) {
                    success.set(true);
                } else {
                    success.set(false);
                }
                latch2.countDown();
            }
        }, null);
        lh.addEntry(("data" + (numEntries - 1)).getBytes());
        latch2.await();
        assertTrue(success.get());
        assertTrue(numCallbacks.get() == 1);
        assertEquals(numEntries - 2, readLh.getLastAddConfirmed());

        success.set(false);
        numCallbacks.set(0);
        final CountDownLatch latch3 = new CountDownLatch(1);
        readLh.asyncReadLastConfirmedAndEntry(1000, false, new AsyncCallback.ReadLastConfirmedAndEntryCallback() {
            @Override
            public void readLastConfirmedAndEntryComplete(int rc, long lastConfirmed, LedgerEntry entry, Object ctx) {
                numCallbacks.incrementAndGet();
                if (BKException.Code.OK == rc && lastConfirmed == (numEntries - 1)) {
                    success.set(true);
                } else {
                    success.set(false);
                }
                latch3.countDown();
            }
        }, null);
        lh.addEntry(("data" + numEntries).getBytes());
        latch3.await();
        assertTrue(success.get());
        assertTrue(numCallbacks.get() == 1);
        assertEquals(numEntries - 1, readLh.getLastAddConfirmed());
        lh.close();
        readLh.close();
    }

    @Test(timeout = 60000)
    public void testReadLACLongPollWhenSomeBookiesDown() throws Exception {
        final int numEntries = 3;
        final LedgerHandle lh = bkc.createLedger(3, 1, 1, digestType, "".getBytes());
        LedgerHandle readLh = bkc.openLedgerNoRecovery(lh.getId(), digestType, "".getBytes());
        assertEquals(LedgerHandle.INVALID_ENTRY_ID, readLh.getLastAddConfirmed());
        // add entries
        for (int i = 0; i < numEntries; i++) {
            lh.addEntry(("data" + i).getBytes());
        }
        for (int i = 0; i < numEntries; i++) {
            ServerConfiguration[] confs = new ServerConfiguration[numEntries - 1];
            for (int j = 0; j < numEntries - 1; j++) {
                int idx = (i + 1 + j) % numEntries;
                confs[j] = killBookie(lh.getLedgerMetadata().currentEnsemble.get(idx));
            }

            final AtomicBoolean entryAsExpected = new AtomicBoolean(false);
            final AtomicBoolean success = new AtomicBoolean(false);
            final AtomicInteger numCallbacks = new AtomicInteger(0);
            final CountDownLatch latch = new CountDownLatch(1);
            final int entryId = i;
            readLh.asyncTryReadLastConfirmed(new AsyncCallback.ReadLastConfirmedCallback() {
                @Override
                public void readLastConfirmedComplete(int rc, long lastConfirmed, Object ctx) {
                    numCallbacks.incrementAndGet();
                    if (BKException.Code.OK == rc) {
                        success.set(true);
                        entryAsExpected.set(lastConfirmed == (entryId - 1));
                    } else {
                        System.out.println("Return value" + rc);
                        success.set(false);
                        entryAsExpected.set(false);
                    }
                    latch.countDown();
                }
            }, null);
            latch.await();
            assertTrue(success.get());
            assertTrue(entryAsExpected.get());
            assertTrue(numCallbacks.get() == 1);

            lh.close();
            readLh.close();

            // start the bookies
            for (ServerConfiguration conf : confs) {
                bs.add(startBookie(conf));
                bsConfs.add(conf);
            }
        }
    }
}