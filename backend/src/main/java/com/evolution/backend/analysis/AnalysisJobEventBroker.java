package com.evolution.backend.analysis;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe per-job pub/sub event broker for live SSE job telemetry.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Bounded per-job replay ring buffer (default 256 events) supporting {@code Last-Event-ID}.</li>
 *   <li>Explicit {@code resyncRequired} signal when a reconnecting client falls behind the ring.</li>
 *   <li>Per-subscriber bounded milestone queue (64 events) plus coalescing
 *       {@link AtomicReference} for high-frequency {@code progress_update} / {@code module_progress}
 *       events so slow clients NEVER block analysis worker threads.</li>
 *   <li>Closing a {@link Subscription} NEVER cancels or affects the underlying analysis job.</li>
 * </ul>
 */
public final class AnalysisJobEventBroker {
    public static final int DEFAULT_RING_CAPACITY = 256;
    public static final int SUBSCRIBER_QUEUE_CAPACITY = 64;
    private static final int MAX_RETAINED_CHANNELS = 128;

    private static final Set<String> COALESCABLE_EVENTS = Set.of("progress_update", "module_progress");
    private static final Set<String> TERMINAL_EVENTS = Set.of("job_completed", "job_failed", "job_cancelled");

    public record JobStreamEvent(long seq, String event, String json, Instant at) {
        public JobStreamEvent {
            if (seq < 0L) {
                throw new IllegalArgumentException("Negative sequence number");
            }
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(at, "at");
        }

        public boolean isTerminal() {
            return TERMINAL_EVENTS.contains(event);
        }
    }

    public static final class Subscription implements AutoCloseable {
        private final String jobId;
        private final Channel channel;
        private final boolean resyncRequired;
        private final long lowestAvailableSeq;
        private final int queueCapacity;

        private final ReentrantLock lock = new ReentrantLock();
        private final Condition available = lock.newCondition();
        private final Deque<JobStreamEvent> queue = new ArrayDeque<>();
        private final AtomicReference<JobStreamEvent> pendingProgress = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private boolean terminalDelivered;
        private long lastDeliveredSeq;

        Subscription(String jobId, Channel channel, boolean resyncRequired, long lowestAvailableSeq,
                List<JobStreamEvent> initialEvents, int queueCapacity, boolean alreadyTerminal) {
            this.jobId = jobId;
            this.channel = channel;
            this.resyncRequired = resyncRequired;
            this.lowestAvailableSeq = lowestAvailableSeq;
            this.queueCapacity = queueCapacity;
            this.terminalDelivered = alreadyTerminal;
            for (var ev : initialEvents) {
                enqueueLocked(ev);
            }
        }

        public String jobId() {
            return jobId;
        }

        public boolean resyncRequired() {
            return resyncRequired;
        }

        public long lowestAvailableSeq() {
            return lowestAvailableSeq;
        }

        public boolean isClosed() {
            return closed.get();
        }

        public boolean isTerminalDelivered() {
            lock.lock();
            try {
                return terminalDelivered;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Polls the next event (prioritizing ordered milestones/replays before coalesced progress),
         * waiting up to {@code timeout} before returning {@code null} (allowing callers to emit an
         * SSE heartbeat comment).
         */
        public JobStreamEvent poll(long timeout, TimeUnit unit) throws InterruptedException {
            Objects.requireNonNull(unit, "unit");
            long remainingNanos = unit.toNanos(Math.max(0L, timeout));
            lock.lock();
            try {
                while (true) {
                    var next = dequeueNextLocked();
                    if (next != null) {
                        if (next.isTerminal()) {
                            terminalDelivered = true;
                        }
                        return next;
                    }
                    if (closed.get() || terminalDelivered) {
                        return null;
                    }
                    if (remainingNanos <= 0L) {
                        return null;
                    }
                    remainingNanos = available.awaitNanos(remainingNanos);
                }
            } finally {
                lock.unlock();
            }
        }

        void deliver(JobStreamEvent event) {
            if (closed.get()) {
                return;
            }
            lock.lock();
            try {
                if (closed.get()) {
                    return;
                }
                if (COALESCABLE_EVENTS.contains(event.event())) {
                    pendingProgress.set(event);
                } else {
                    enqueueLocked(event);
                }
                available.signalAll();
            } finally {
                lock.unlock();
            }
        }

        void signalClosedByBroker() {
            lock.lock();
            try {
                available.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void enqueueLocked(JobStreamEvent event) {
            if (queue.size() >= queueCapacity) {
                // Drop the oldest non-terminal queued event so producer never stalls and terminal events survive.
                JobStreamEvent removed = null;
                for (var it = queue.iterator(); it.hasNext(); ) {
                    var candidate = it.next();
                    if (!candidate.isTerminal()) {
                        it.remove();
                        removed = candidate;
                        break;
                    }
                }
                if (removed == null && !queue.isEmpty()) {
                    queue.removeFirst();
                }
            }
            queue.addLast(event);
        }

        private JobStreamEvent dequeueNextLocked() {
            if (!queue.isEmpty()) {
                var ev = queue.removeFirst();
                if (ev.seq() > lastDeliveredSeq) {
                    lastDeliveredSeq = ev.seq();
                }
                return ev;
            }
            var progress = pendingProgress.getAndSet(null);
            if (progress == null) {
                return null;
            }
            if (progress.seq() <= lastDeliveredSeq) {
                return null;
            }
            lastDeliveredSeq = progress.seq();
            return progress;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                if (channel != null) {
                    channel.subscribers.remove(this);
                }
                lock.lock();
                try {
                    queue.clear();
                    pendingProgress.set(null);
                    available.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    static final class Channel {
        final String jobId;
        final int ringCapacity;
        final Deque<JobStreamEvent> ring = new ArrayDeque<>();
        final CopyOnWriteArrayList<Subscription> subscribers = new CopyOnWriteArrayList<>();
        long sequence = 0L;
        boolean terminal = false;

        Channel(String jobId, int ringCapacity) {
            this.jobId = jobId;
            this.ringCapacity = ringCapacity;
        }
    }

    private final int ringCapacity;
    private final int subscriberQueueCapacity;
    private final LinkedHashMap<String, Channel> channels = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Channel> eldest) {
            return size() > MAX_RETAINED_CHANNELS && eldest.getValue().terminal && eldest.getValue().subscribers.isEmpty();
        }
    };

    public AnalysisJobEventBroker() {
        this(DEFAULT_RING_CAPACITY, SUBSCRIBER_QUEUE_CAPACITY);
    }

    public AnalysisJobEventBroker(int ringCapacity) {
        this(ringCapacity, SUBSCRIBER_QUEUE_CAPACITY);
    }

    public AnalysisJobEventBroker(int ringCapacity, int subscriberQueueCapacity) {
        if (ringCapacity < 1 || ringCapacity > 4096) {
            throw new IllegalArgumentException("ringCapacity must be between 1 and 4096");
        }
        if (subscriberQueueCapacity < 2 || subscriberQueueCapacity > 1024) {
            throw new IllegalArgumentException("subscriberQueueCapacity must be between 2 and 1024");
        }
        this.ringCapacity = ringCapacity;
        this.subscriberQueueCapacity = subscriberQueueCapacity;
    }

    public JobStreamEvent publish(String jobId, String eventType, Object payload) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(eventType, "eventType");
        String json = payload instanceof String raw ? raw : CanonicalJson.write(payload);
        JobStreamEvent event;
        Channel channel;
        synchronized (this) {
            channel = channels.computeIfAbsent(jobId, id -> new Channel(id, ringCapacity));
            long seq = ++channel.sequence;
            event = new JobStreamEvent(seq, eventType, json, Instant.now());
            if (channel.ring.size() >= channel.ringCapacity) {
                channel.ring.removeFirst();
            }
            channel.ring.addLast(event);
            if (event.isTerminal()) {
                channel.terminal = true;
            }
            for (var subscriber : channel.subscribers) {
                subscriber.deliver(event);
            }
            pruneTerminalChannelsLocked(jobId);
        }
        return event;
    }

    private void pruneTerminalChannelsLocked(String keepJobId) {
        if (channels.size() <= MAX_RETAINED_CHANNELS) {
            return;
        }
        var it = channels.entrySet().iterator();
        while (it.hasNext() && channels.size() > MAX_RETAINED_CHANNELS) {
            var entry = it.next();
            if (!entry.getKey().equals(keepJobId)
                    && entry.getValue().terminal
                    && entry.getValue().subscribers.isEmpty()) {
                it.remove();
            }
        }
    }

    /**
     * Subscribes to a job's live SSE feed.
     *
     * @param jobId job identifier
     * @param lastEventId {@code 0} for fresh stream, or positive sequence from {@code Last-Event-ID}
     * @param initialSnapshot optional snapshot payload delivered first when {@code lastEventId == 0}
     *                        or when {@code resyncRequired} is true
     */
    public Subscription subscribe(String jobId, long lastEventId, Optional<Object> initialSnapshot) {
        Objects.requireNonNull(jobId, "jobId");
        if (lastEventId < 0L) {
            throw new IllegalArgumentException("Negative lastEventId");
        }
        Objects.requireNonNull(initialSnapshot, "initialSnapshot");

        synchronized (this) {
            var channel = channels.computeIfAbsent(jobId, id -> new Channel(id, ringCapacity));
            long lowestSeq = channel.ring.isEmpty() ? (channel.sequence + 1L) : channel.ring.peekFirst().seq();
            boolean resync = false;
            var initialEvents = new ArrayList<JobStreamEvent>();

            if (lastEventId == 0L) {
                long snapSeq = channel.sequence;
                if (initialSnapshot.isPresent()) {
                    var snapObj = initialSnapshot.orElseThrow();
                    String snapJson = snapObj instanceof String s ? s : CanonicalJson.write(snapObj);
                    initialEvents.add(new JobStreamEvent(snapSeq, "snapshot", snapJson, Instant.now()));
                }
                // If already terminal, ensure the terminal event follows the snapshot so the client closes cleanly.
                if (channel.terminal) {
                    JobStreamEvent terminalEvent = findLastTerminal(channel);
                    if (terminalEvent != null) {
                        initialEvents.add(terminalEvent);
                    }
                }
            } else {
                if (lastEventId > channel.sequence || (!channel.ring.isEmpty() && lastEventId + 1L < lowestSeq)) {
                    resync = true;
                    String resyncJson = CanonicalJson.write(Map.of(
                            "jobId", jobId,
                            "requestedLastEventId", lastEventId,
                            "lowestAvailableSeq", lowestSeq,
                            "currentSeq", channel.sequence,
                            "resyncRequired", true));
                    initialEvents.add(new JobStreamEvent(channel.sequence, "resync_required", resyncJson, Instant.now()));
                    if (initialSnapshot.isPresent()) {
                        var snapObj = initialSnapshot.orElseThrow();
                        String snapJson = snapObj instanceof String s ? s : CanonicalJson.write(snapObj);
                        initialEvents.add(new JobStreamEvent(channel.sequence, "snapshot", snapJson, Instant.now()));
                    }
                    if (channel.terminal) {
                        JobStreamEvent terminalEvent = findLastTerminal(channel);
                        if (terminalEvent != null) {
                            initialEvents.add(terminalEvent);
                        }
                    }
                } else {
                    for (var buffered : channel.ring) {
                        if (buffered.seq() > lastEventId) {
                            initialEvents.add(buffered);
                        }
                    }
                }
            }

            boolean alreadyTerminal = channel.terminal && initialEvents.stream().noneMatch(JobStreamEvent::isTerminal);
            var subscription = new Subscription(jobId, channel, resync, lowestSeq, initialEvents,
                    subscriberQueueCapacity, alreadyTerminal);
            if (!channel.terminal) {
                channel.subscribers.add(subscription);
            }
            return subscription;
        }
    }

    /**
     * Builds a synthetic terminal subscription for historical jobs whose channel is no longer in RAM
     * (e.g. after server restart). Delivers {@code snapshot} + terminal event and closes cleanly.
     */
    public Subscription historicalTerminalSubscription(String jobId, Object snapshotPayload, String terminalEventType,
            Object terminalPayload) {
        Objects.requireNonNull(jobId, "jobId");
        String snapJson = snapshotPayload instanceof String s ? s : CanonicalJson.write(snapshotPayload);
        String termJson = terminalPayload instanceof String s ? s : CanonicalJson.write(terminalPayload);
        Instant now = Instant.now();
        var events = List.of(
                new JobStreamEvent(1L, "snapshot", snapJson, now),
                new JobStreamEvent(2L, terminalEventType, termJson, now));
        return new Subscription(jobId, null, false, 1L, events, subscriberQueueCapacity, false);
    }

    public synchronized boolean hasChannel(String jobId) {
        return channels.containsKey(jobId);
    }

    public synchronized int subscriberCount(String jobId) {
        var channel = channels.get(jobId);
        return channel == null ? 0 : channel.subscribers.size();
    }

    public synchronized List<JobStreamEvent> recentEvents(String jobId) {
        var channel = channels.get(jobId);
        return channel == null ? List.of() : List.copyOf(channel.ring);
    }

    public synchronized void closeAll() {
        for (var channel : channels.values()) {
            for (var sub : channel.subscribers) {
                sub.close();
            }
            channel.subscribers.clear();
        }
    }

    private static JobStreamEvent findLastTerminal(Channel channel) {
        JobStreamEvent last = null;
        for (var ev : channel.ring) {
            if (ev.isTerminal()) {
                last = ev;
            }
        }
        return last;
    }
}
