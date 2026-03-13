/**
 * StreamQueue – serializes go2rtc HLS pipeline startups.
 *
 * go2rtc opens one RTSP pipeline per stream.  When multiple cameras request
 * their m3u8 simultaneously the server races and only the first one wins; the
 * others never get a manifest.
 *
 * Strategy: camera N is only allowed to call loadSource() after camera N-1 has
 * either received MANIFEST_PARSED **or** timed-out waiting for it.
 *
 * Each camera registers itself, gets back a `permit()` promise that resolves
 * when it is its turn, and calls `done()` (success) or `fail()` when finished.
 */

const GO2RTC_OPEN_TIMEOUT_MS = 15_000; // max time to wait for one pipeline

type Resolve = () => void;

interface Slot {
    resolve: Resolve;
}

class StreamQueue {
    private queue: Slot[] = [];
    private currentSlot: Slot | null = null;
    private currentTimer: ReturnType<typeof setTimeout> | null = null;

    /**
     * Reserve a place in the queue.
     * Returns a promise that resolves when this camera may start loading.
     * Must call release() when the stream is ready or has failed.
     */
    enqueue(): { permit: Promise<void>; release: () => void } {
        let resolve!: Resolve;
        const permit = new Promise<void>((res) => { resolve = res; });
        const slot: Slot = { resolve };
        this.queue.push(slot);

        this.maybeNext();

        let released = false;
        const release = () => {
            if (released) return;
            released = true;
            if (this.currentSlot === slot) {
                // It's the active slot, so advance queue
                this.advance();
            } else {
                // It was waiting, just remove it from the queue
                this.queue = this.queue.filter(s => s !== slot);
            }
        };
        return { permit, release };
    }

    private maybeNext() {
        if (this.currentSlot) return; // Something is running
        const slot = this.queue.shift();
        if (!slot) return;

        this.currentSlot = slot;
        slot.resolve();

        // Safety: if the current camera never calls release(), unblock after timeout
        this.currentTimer = setTimeout(() => {
            console.warn('[StreamQueue] Timeout waiting for release – advancing');
            this.advance();
        }, GO2RTC_OPEN_TIMEOUT_MS);
    }

    private advance() {
        if (this.currentTimer) {
            clearTimeout(this.currentTimer);
            this.currentTimer = null;
        }
        this.currentSlot = null;
        this.maybeNext();
    }
}

// Singleton – shared across all LiveCamera instances on the page
const streamQueue = new StreamQueue();
export default streamQueue;
