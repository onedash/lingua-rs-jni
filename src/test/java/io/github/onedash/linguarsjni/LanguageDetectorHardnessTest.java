package io.github.onedash.linguarsjni;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageDetectorHardnessTest {
    private static final List<String> TEXTS = List.of(
            "The quick brown fox jumps over the lazy dog",
            "Bonjour tout le monde, comment allez-vous aujourd'hui?",
            "Der schnelle braune Fuchs springt uber den faulen Hund",
            "Esta frase se utiliza para comprobar el detector de idiomas"
    );

    private static LanguageDetector detector;

    @BeforeAll
    static void createDetector() {
        detector = LanguageDetectorBuilder.fromAllLanguages()
                .withPreloadedLanguageModels()
                .build();
    }

    @AfterAll
    static void closeDetector() {
        detector.close();
    }

    @Test
    void survivesConcurrentLoad() throws Exception {
        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<Language>> calls = IntStream.range(0, 1_200)
                    .mapToObj(index -> (Callable<Language>) () ->
                            detector.detectLanguageOf(TEXTS.get(index % TEXTS.size())))
                    .toList();
            for (var result : executor.invokeAll(calls)) {
                assertThat(result.get()).isNotEqualTo(Language.UNKNOWN);
            }
        }
    }

    @Test
    void keepsLanguageModelsOutsideTheJvmHeap() throws Exception {
        long before = usedHeapAfterGc();
        for (int index = 0; index < 200; index++) {
            detector.computeLanguageConfidenceValues(TEXTS.get(index % TEXTS.size()));
        }
        long growth = usedHeapAfterGc() - before;
        assertThat(growth).isLessThan(96L * 1024 * 1024);
    }

    @Test
    void repeatedlyCreatesAndClosesDetectors() {
        for (int index = 0; index < 30; index++) {
            try (LanguageDetector temporary = LanguageDetectorBuilder.fromLanguages(
                    Language.ENGLISH, Language.FRENCH, Language.GERMAN
            ).build()) {
                assertThat(temporary.detectLanguageOf("This is a lifecycle test"))
                        .isEqualTo(Language.ENGLISH);
            }
        }
    }

    /**
     * A close racing an in-flight detection must surface as an exception, never as a crash:
     * the native handle is a registry key, so a stale one is always detectable.
     */
    @Test
    void survivesCloseRacingDetection() throws Exception {
        for (int round = 0; round < 20; round++) {
            LanguageDetector victim = LanguageDetectorBuilder
                    .fromLanguages(Language.ENGLISH, Language.FRENCH, Language.GERMAN)
                    .build();
            var start = new java.util.concurrent.CountDownLatch(1);
            var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            var threads = new java.util.ArrayList<Thread>();
            for (int worker = 0; worker < 8; worker++) {
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                        for (int call = 0; call < 50; call++) {
                            victim.detectLanguageOf("This is an English sentence");
                        }
                    } catch (IllegalStateException expected) {
                        // The detector was closed underneath us; that is the contract.
                    } catch (Throwable unexpected) {
                        failures.add(unexpected);
                    }
                });
                threads.add(thread);
                thread.start();
            }
            start.countDown();
            Thread.sleep(1);
            victim.close();
            for (Thread thread : threads) {
                thread.join();
            }
            assertThat(failures).isEmpty();
        }
    }

    /**
     * Without a reachability fence the cleaner can free the native detector while a detection
     * that no longer touches `this` is still running.
     */
    @Test
    void survivesGarbageCollectionDuringDetection() {
        LanguageDetector unreferenced = LanguageDetectorBuilder
                .fromLanguages(Language.ENGLISH, Language.FRENCH)
                .build();
        for (int index = 0; index < 200; index++) {
            System.gc();
            assertThat(unreferenced.detectLanguageOf("This is an English sentence"))
                    .isEqualTo(Language.ENGLISH);
        }
        unreferenced.close();
    }

    private static long usedHeapAfterGc() throws InterruptedException {
        System.gc();
        Thread.sleep(100);
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
