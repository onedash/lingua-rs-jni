package io.github.onedash.linguarsjni.internal;

/**
 * Raw JNI entry points. Not part of the public API: signatures may change in any release,
 * and passing a handle that was not returned by {@link #create} is a programming error.
 *
 * <p>Handles are opaque registry keys rather than pointers, so a stale handle raises
 * {@link IllegalStateException} instead of corrupting the process.
 */
public final class NativeDetector {
    static {
        NativeLibraryLoader.load();
    }

    private NativeDetector() {}

    /**
     * @param languageNames names of {@code io.github.onedash.linguarsjni.Language} constants,
     *                      without duplicates
     * @return a handle to pass to the other methods
     */
    public static native long create(
            String[] languageNames,
            double minimumRelativeDistance,
            boolean lowAccuracy,
            boolean preload
    );

    /**
     * @return the index into the {@code languageNames} given to {@link #create}, or {@code -1}
     *         when no language could be detected
     */
    public static native int detect(long handle, String text);

    /** @return one confidence per language, in the order given to {@link #create} */
    public static native double[] confidenceValues(long handle, String text);

    /** Releases the native detector. Idempotent; unknown handles are ignored. */
    public static native void destroy(long handle);
}
