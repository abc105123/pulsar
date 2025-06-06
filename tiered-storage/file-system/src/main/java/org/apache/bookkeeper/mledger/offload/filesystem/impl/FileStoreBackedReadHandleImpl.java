/*
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
 */
package org.apache.bookkeeper.mledger.offload.filesystem.impl;

import static org.apache.bookkeeper.mledger.offload.OffloadUtils.parseLedgerMetadata;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.api.LastConfirmedAndEntry;
import org.apache.bookkeeper.client.api.LedgerEntries;
import org.apache.bookkeeper.client.api.LedgerEntry;
import org.apache.bookkeeper.client.api.LedgerMetadata;
import org.apache.bookkeeper.client.api.ReadHandle;
import org.apache.bookkeeper.client.impl.LedgerEntriesImpl;
import org.apache.bookkeeper.client.impl.LedgerEntryImpl;
import org.apache.bookkeeper.mledger.LedgerOffloaderStats;
import org.apache.bookkeeper.mledger.ManagedLedgerException;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.MapFile;
import org.apache.pulsar.common.allocator.PulsarByteBufAllocator;
import org.apache.pulsar.common.naming.TopicName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileStoreBackedReadHandleImpl implements ReadHandle {
    private static final Logger log = LoggerFactory.getLogger(FileStoreBackedReadHandleImpl.class);
    private final ExecutorService executor;
    private final MapFile.Reader reader;
    private final long ledgerId;
    private final LedgerMetadata ledgerMetadata;
    private final LedgerOffloaderStats offloaderStats;
    private final String managedLedgerName;
    private final String topicName;
    enum State {
        Opened,
        Closed
    }
    private volatile State state;
    private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();

    private FileStoreBackedReadHandleImpl(ExecutorService executor, MapFile.Reader reader, long ledgerId,
                                          LedgerOffloaderStats offloaderStats,
                                          String managedLedgerName) throws IOException {
        this.ledgerId = ledgerId;
        this.executor = executor;
        this.reader = reader;
        this.offloaderStats = offloaderStats;
        this.managedLedgerName = managedLedgerName;
        this.topicName = TopicName.fromPersistenceNamingEncoding(managedLedgerName);
        LongWritable key = new LongWritable();
        BytesWritable value = new BytesWritable();
        try {
            key.set(FileSystemManagedLedgerOffloader.METADATA_KEY_INDEX);
            long startReadIndexTime = System.nanoTime();
            reader.get(key, value);
            offloaderStats.recordReadOffloadIndexLatency(topicName,
                    System.nanoTime() - startReadIndexTime, TimeUnit.NANOSECONDS);
            this.ledgerMetadata = parseLedgerMetadata(ledgerId, value.copyBytes());
            state = State.Opened;
        } catch (IOException e) {
            log.error("Fail to read LedgerMetadata for ledgerId {}",
                    ledgerId);
            throw new IOException("Fail to read LedgerMetadata for ledgerId " + key.get());
        }
    }

    @Override
    public long getId() {
        return ledgerId;
    }

    @Override
    public LedgerMetadata getLedgerMetadata() {
        return ledgerMetadata;

    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        if (closeFuture.get() != null || !closeFuture.compareAndSet(null, new CompletableFuture<>())) {
            return closeFuture.get();
        }

        CompletableFuture<Void> promise = closeFuture.get();
        executor.execute(() -> {
            try {
                reader.close();
                state = State.Closed;
                promise.complete(null);
            } catch (IOException t) {
                promise.completeExceptionally(t);
            }
        });
        return promise;
    }

    @Override
    public CompletableFuture<LedgerEntries> readAsync(long firstEntry, long lastEntry) {
        if (log.isDebugEnabled()) {
            log.debug("Ledger {}: reading {} - {}", getId(), firstEntry, lastEntry);
        }
        CompletableFuture<LedgerEntries> promise = new CompletableFuture<>();
        executor.execute(() -> {
            if (state == State.Closed) {
                log.warn("Reading a closed read handler. Ledger ID: {}, Read range: {}-{}",
                        ledgerId, firstEntry, lastEntry);
                promise.completeExceptionally(new ManagedLedgerException.OffloadReadHandleClosedException());
                return;
            }
            if (firstEntry > lastEntry
                    || firstEntry < 0
                    || lastEntry > getLastAddConfirmed()) {
                promise.completeExceptionally(new BKException.BKIncorrectParameterException());
                return;
            }
            long entriesToRead = (lastEntry - firstEntry) + 1;
            List<LedgerEntry> entries = new ArrayList<LedgerEntry>();
            long nextExpectedId = firstEntry;
            LongWritable key = new LongWritable();
            BytesWritable value = new BytesWritable();
            try {
                key.set(nextExpectedId - 1);
                reader.seek(key);
                while (entriesToRead > 0) {
                    long startReadTime = System.nanoTime();
                    reader.next(key, value);
                    this.offloaderStats.recordReadOffloadDataLatency(topicName,
                            System.nanoTime() - startReadTime, TimeUnit.NANOSECONDS);
                    int length = value.getLength();
                    long entryId = key.get();
                    if (entryId == nextExpectedId) {
                        ByteBuf buf = PulsarByteBufAllocator.DEFAULT.buffer(length, length);
                        entries.add(LedgerEntryImpl.create(ledgerId, entryId, length, buf));
                        buf.writeBytes(value.copyBytes());
                        entriesToRead--;
                        nextExpectedId++;
                        this.offloaderStats.recordReadOffloadBytes(topicName, length);
                    } else if (entryId > lastEntry) {
                        log.info("Expected to read {}, but read {}, which is greater than last entry {}",
                                nextExpectedId, entryId, lastEntry);
                        throw new BKException.BKUnexpectedConditionException();
                    }
                }
                promise.complete(LedgerEntriesImpl.create(entries));
            } catch (Throwable t) {
                this.offloaderStats.recordReadOffloadError(topicName);
                promise.completeExceptionally(t);
                entries.forEach(LedgerEntry::close);
            }
        });
        return promise;
    }

    @Override
    public CompletableFuture<LedgerEntries> readUnconfirmedAsync(long firstEntry, long lastEntry) {
        return readAsync(firstEntry, lastEntry);
    }

    @Override
    public CompletableFuture<Long> readLastAddConfirmedAsync() {
        return CompletableFuture.completedFuture(getLastAddConfirmed());
    }

    @Override
    public CompletableFuture<Long> tryReadLastAddConfirmedAsync() {
        return CompletableFuture.completedFuture(getLastAddConfirmed());
    }

    @Override
    public long getLastAddConfirmed() {
        return getLedgerMetadata().getLastEntryId();
    }

    @Override
    public long getLength() {
        return getLedgerMetadata().getLength();
    }

    @Override
    public boolean isClosed() {
        return getLedgerMetadata().isClosed();
    }

    @Override
    public CompletableFuture<LastConfirmedAndEntry> readLastAddConfirmedAndEntryAsync(long entryId,
                                                                                      long timeOutInMillis,
                                                                                      boolean parallel) {
        CompletableFuture<LastConfirmedAndEntry> promise = new CompletableFuture<>();
        promise.completeExceptionally(new UnsupportedOperationException());
        return promise;
    }

    public static ReadHandle open(ScheduledExecutorService executor, MapFile.Reader reader, long ledgerId,
                                  LedgerOffloaderStats offloaderStats, String managedLedgerName) throws IOException {
        return new FileStoreBackedReadHandleImpl(executor, reader, ledgerId, offloaderStats, managedLedgerName);
    }
}
