package io.github.onedash.linguarsjni;

import io.github.onedash.linguarsjni.internal.NativeDetector;

import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe Java facade over a native Lingua detector.
 *
 * <p>Detectors are expensive to build and cheap to share: create one per language set at
 * startup and reuse it for the lifetime of the process. Detection holds no Java lock, and the
 * language models live in the native library's read-only data rather than on the JVM heap.
 *
 * <p>Closing is optional. {@link #close()} releases the native detector immediately; if it is
 * never called, a {@link Cleaner} releases it once this object becomes unreachable. Calling a
 * detection method after {@link #close()} throws {@link IllegalStateException} rather than
 * crashing the JVM, including when the close races with an in-flight detection on another
 * thread.
 */
public final class LanguageDetector implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();

    private final List<Language> languages;
    private final State state;
    private final Cleaner.Cleanable cleanable;

    LanguageDetector(
            Collection<Language> languages,
            double minimumRelativeDistance,
            boolean lowAccuracy,
            boolean preload
    ) {
        this.languages = List.copyOf(languages);
        String[] names = this.languages.stream().map(Language::name).toArray(String[]::new);
        this.state = new State(NativeDetector.create(names, minimumRelativeDistance, lowAccuracy, preload));
        this.cleanable = CLEANER.register(this, state);
    }

    /** The languages this detector chooses between, in the order used by the confidence map. */
    public List<Language> getLanguages() {
        return languages;
    }

    /**
     * @return the detected language, or {@link Language#UNKNOWN} when the text carries no
     *         letters or no language is a clear enough winner
     */
    public Language detectLanguageOf(String text) {
        Objects.requireNonNull(text, "text");
        if (!hasLetters(text)) {
            return Language.UNKNOWN;
        }
        long handle = state.handle();
        try {
            int index = NativeDetector.detect(handle, text);
            if (index < 0) {
                return Language.UNKNOWN;
            }
            if (index >= languages.size()) {
                throw new IllegalStateException("Native detector returned language index " + index
                        + " for a detector with " + languages.size() + " languages");
            }
            return languages.get(index);
        } finally {
            // `this` is unreachable from here on as far as the JIT is concerned, so without a
            // fence the Cleaner could destroy the native detector while the call above runs.
            Reference.reachabilityFence(this);
        }
    }

    /**
     * @return confidences summing to 1.0, ordered by descending confidence then by language.
     *         Empty when the text carries no letters. Languages this detector was not built
     *         with are absent; looking one up returns {@code null} rather than throwing.
     */
    public SortedMap<Language, Double> computeLanguageConfidenceValues(String text) {
        Objects.requireNonNull(text, "text");
        Map<Language, Double> scores = new EnumMap<>(Language.class);
        if (hasLetters(text)) {
            long handle = state.handle();
            double[] values;
            try {
                values = NativeDetector.confidenceValues(handle, text);
            } finally {
                Reference.reachabilityFence(this);
            }
            if (values.length != languages.size()) {
                throw new IllegalStateException("Native confidence result has " + values.length
                        + " entries but the detector has " + languages.size() + " languages");
            }
            for (int index = 0; index < values.length; index++) {
                scores.put(languages.get(index), values[index]);
            }
        }
        SortedMap<Language, Double> sorted = new TreeMap<>(byDescendingConfidence(scores));
        sorted.putAll(scores);
        return sorted;
    }

    /**
     * @return the confidence for a single language, or {@code 0.0} when this detector was not
     *         built with it
     */
    public double computeLanguageConfidence(String text, Language language) {
        Objects.requireNonNull(language, "language");
        return computeLanguageConfidenceValues(text).getOrDefault(language, 0.0);
    }

    /** Releases the native detector. Idempotent. */
    @Override
    public void close() {
        cleanable.clean();
    }

    /**
     * Total order over every {@link Language}, not just the ones present in {@code scores}, so
     * that {@code get}/{@code containsKey} on the returned map answer for absent languages
     * instead of dereferencing a null confidence.
     */
    private static Comparator<Language> byDescendingConfidence(Map<Language, Double> scores) {
        return Comparator
                .<Language>comparingDouble(language -> scores.getOrDefault(language, Double.NEGATIVE_INFINITY))
                .reversed()
                .thenComparing(Comparator.naturalOrder());
    }

    /** Mirrors the {@code \p{L}}-based tokenizer in lingua: no letters means no detection. */
    private static boolean hasLetters(String text) {
        return text.codePoints().anyMatch(Character::isLetter);
    }

    /**
     * Holds the native handle. Deliberately does not reference the enclosing detector, which
     * would keep it reachable forever and defeat the {@link Cleaner}.
     */
    private static final class State implements Runnable {
        private final AtomicLong handle;

        private State(long handle) {
            if (handle == 0) {
                throw new IllegalStateException("Native detector creation failed");
            }
            this.handle = new AtomicLong(handle);
        }

        private long handle() {
            long value = handle.get();
            if (value == 0) {
                throw new IllegalStateException("Language detector is closed");
            }
            return value;
        }

        @Override
        public void run() {
            long value = handle.getAndSet(0);
            if (value != 0) {
                NativeDetector.destroy(value);
            }
        }
    }
}
