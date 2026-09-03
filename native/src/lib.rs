//! JNI bindings for `lingua-rs`.
//!
//! Design notes that matter for high-load services:
//!
//! * Detectors live in a process-wide registry keyed by an opaque `i64` handle instead of
//!   being handed to Java as a raw pointer. A stale handle therefore produces a Java
//!   exception rather than undefined behaviour when `close()` races with a detection call.
//! * The registry is an `RwLock`, so concurrent detections take a shared lock only long
//!   enough to clone an `Arc`; the detection itself runs without holding any lock.
//! * Handles hash through a trivial multiplicative hasher. Ids are process-local counters,
//!   never attacker-controlled, so SipHash would only add latency.
//! * `detect` returns an index into the language list supplied at creation time rather than
//!   a name, which keeps the hot path free of string formatting and JVM string allocation.
//! * Every entry point is wrapped in `catch_unwind`; a panic unwinding across the `extern`
//!   boundary would be undefined behaviour.

use jni::objects::{JClass, JDoubleArray, JObjectArray, JString};
use jni::sys::{jboolean, jdouble, jdoubleArray, jint, jlong};
use jni::JNIEnv;
use lingua::{Language, LanguageDetector, LanguageDetectorBuilder};
use std::collections::HashMap;
use std::hash::{BuildHasherDefault, Hasher};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, OnceLock, RwLock};

/// Multiplicative hasher for the sequential detector ids. See the module docs.
#[derive(Default)]
struct IdHasher(u64);

impl Hasher for IdHasher {
    fn finish(&self) -> u64 {
        self.0
    }

    fn write(&mut self, bytes: &[u8]) {
        for byte in bytes {
            self.0 = (self.0 ^ u64::from(*byte)).wrapping_mul(0x0100_0000_01b3);
        }
    }

    fn write_i64(&mut self, value: i64) {
        // Golden-ratio multiply: spreads the sequential ids over the high bits that
        // hashbrown uses for its control bytes.
        self.0 = (value as u64).wrapping_mul(0x9E37_79B9_7F4A_7C15);
    }
}

type Registry = HashMap<i64, Arc<Detector>, BuildHasherDefault<IdHasher>>;

struct Detector {
    inner: LanguageDetector,
    language_count: usize,
    /// `position[language as usize]` is the index of that language in `languages`,
    /// or `u32::MAX` when the detector was not built with it.
    position: Vec<u32>,
}

impl Detector {
    fn new(languages: Vec<Language>, inner: LanguageDetector) -> Self {
        let mut position = vec![u32::MAX; Language::all().len()];
        for (index, language) in languages.iter().enumerate() {
            position[*language as usize] = index as u32;
        }
        Detector {
            inner,
            language_count: languages.len(),
            position,
        }
    }

    /// Reorders lingua's confidence-sorted result into the creation order Java expects.
    fn confidences_in_creation_order(&self, text: String) -> Vec<f64> {
        let mut values = vec![0.0_f64; self.language_count];
        for (language, confidence) in self.inner.compute_language_confidence_values(text) {
            let slot = self.position[language as usize];
            if slot != u32::MAX {
                values[slot as usize] = confidence;
            }
        }
        values
    }
}

fn registry() -> &'static RwLock<Registry> {
    static REGISTRY: OnceLock<RwLock<Registry>> = OnceLock::new();
    REGISTRY.get_or_init(|| RwLock::new(Registry::default()))
}

static NEXT_ID: AtomicI64 = AtomicI64::new(1);

/// Runs `action`, converting both `Err` and panics into a Java exception.
///
/// A pending exception is never overwritten: the first failure carries the most specific
/// diagnosis, and calling `ThrowNew` on top of a pending exception loses it.
fn with_error<T: Copy>(
    env: &mut JNIEnv,
    fallback: T,
    action: impl FnOnce(&mut JNIEnv) -> Result<T, Failure>,
) -> T {
    let outcome = catch_unwind(AssertUnwindSafe(|| action(env)));
    let (class, message) = match outcome {
        Ok(Ok(value)) => return value,
        Ok(Err(failure)) => (failure.class, failure.message),
        Err(panic) => (
            "java/lang/IllegalStateException",
            format!(
                "panic in the lingua native library: {}",
                panic_message(&panic)
            ),
        ),
    };

    if !matches!(env.exception_check(), Ok(true)) {
        let _ = env.throw_new(class, message);
    }
    fallback
}

fn panic_message(panic: &Box<dyn std::any::Any + Send>) -> String {
    if let Some(text) = panic.downcast_ref::<&str>() {
        (*text).to_owned()
    } else if let Some(text) = panic.downcast_ref::<String>() {
        text.clone()
    } else {
        "unknown cause".to_owned()
    }
}

/// A Java exception to throw: the class to instantiate plus its message.
struct Failure {
    class: &'static str,
    message: String,
}

impl Failure {
    fn state(message: impl Into<String>) -> Self {
        Failure {
            class: "java/lang/IllegalStateException",
            message: message.into(),
        }
    }

    fn argument(message: impl Into<String>) -> Self {
        Failure {
            class: "java/lang/IllegalArgumentException",
            message: message.into(),
        }
    }
}

impl From<jni::errors::Error> for Failure {
    fn from(error: jni::errors::Error) -> Self {
        match error {
            // The only null reference any entry point can receive is the text argument.
            jni::errors::Error::NullPtr(_) => Failure {
                class: "java/lang/NullPointerException",
                message: "text".to_owned(),
            },
            other => Failure::state(other.to_string()),
        }
    }
}

fn get_detector(id: i64) -> Result<Arc<Detector>, Failure> {
    // A poisoned registry only means some caller panicked while the tiny insert/remove
    // critical section was on the stack; the map itself is still consistent, so recovering
    // is strictly better than bricking the library for the rest of the process lifetime.
    registry()
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .get(&id)
        .cloned()
        .ok_or_else(|| Failure::state("language detector is closed"))
}

fn read_text(env: &mut JNIEnv, text: &JString) -> Result<String, Failure> {
    Ok(env.get_string(text)?.into())
}

#[no_mangle]
pub extern "system" fn Java_io_github_onedash_linguarsjni_internal_NativeDetector_create(
    mut env: JNIEnv,
    _class: JClass,
    language_names: JObjectArray,
    minimum_relative_distance: jdouble,
    low_accuracy: jboolean,
    preload: jboolean,
) -> jlong {
    with_error(&mut env, 0, |env| {
        if !(0.0..=0.99).contains(&minimum_relative_distance) {
            return Err(Failure::argument(format!(
                "minimum relative distance must be between 0.0 and 0.99, was {minimum_relative_distance}"
            )));
        }

        let count = env.get_array_length(&language_names)?;
        if count == 0 {
            return Err(Failure::argument("at least one language is required"));
        }

        let mut languages: Vec<Language> = Vec::with_capacity(count as usize);
        let mut seen = vec![false; Language::all().len()];
        for index in 0..count {
            let element = env.get_object_array_element(&language_names, index)?;
            let name: String = env.get_string(&JString::from(element))?.into();
            // `Language` derives strum's `EnumString` with `ascii_case_insensitive`, so the
            // Java enum constant name parses directly; no case juggling on the Java side.
            let language = name.parse::<Language>().map_err(|_| {
                Failure::argument(format!(
                    "language {name} is not supported by this build of the native library"
                ))
            })?;
            if std::mem::replace(&mut seen[language as usize], true) {
                return Err(Failure::argument(format!("duplicate language: {name}")));
            }
            languages.push(language);
        }

        let mut builder = LanguageDetectorBuilder::from_languages(&languages);
        builder.with_minimum_relative_distance(minimum_relative_distance);
        if low_accuracy != 0 {
            builder.with_low_accuracy_mode();
        }
        if preload != 0 {
            builder.with_preloaded_language_models();
        }
        let detector = Arc::new(Detector::new(languages, builder.build()));

        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        registry()
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(id, detector);
        Ok(id)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_onedash_linguarsjni_internal_NativeDetector_detect(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    text: JString,
) -> jint {
    with_error(&mut env, -1, |env| {
        let text = read_text(env, &text)?;
        let detector = get_detector(id)?;
        Ok(match detector.inner.detect_language_of(text) {
            // `position` is built from the same list Java holds, so the index is always valid.
            Some(language) => detector.position[language as usize] as jint,
            None => -1,
        })
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_onedash_linguarsjni_internal_NativeDetector_confidenceValues(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    text: JString,
) -> jdoubleArray {
    with_error(&mut env, std::ptr::null_mut(), |env| {
        let text = read_text(env, &text)?;
        let detector = get_detector(id)?;
        let values = detector.confidences_in_creation_order(text);
        let result: JDoubleArray = env.new_double_array(values.len() as i32)?;
        env.set_double_array_region(&result, 0, &values)?;
        Ok(result.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_onedash_linguarsjni_internal_NativeDetector_destroy(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
) {
    with_error(&mut env, (), |_env| {
        // Dropping outside the lock keeps the (potentially slow) model teardown off the
        // critical section that concurrent detections contend on.
        let removed = registry()
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(&id);
        drop(removed);
        Ok(())
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn detector_of(languages: &[Language]) -> Detector {
        Detector::new(
            languages.to_vec(),
            LanguageDetectorBuilder::from_languages(languages).build(),
        )
    }

    #[test]
    fn detects_english() {
        let detector = detector_of(&[Language::English, Language::French]);
        assert_eq!(
            detector
                .inner
                .detect_language_of("This is an English sentence"),
            Some(Language::English)
        );
    }

    #[test]
    fn java_enum_constant_names_parse_case_insensitively() {
        for language in Language::all() {
            let java_name = format!("{language:?}").to_uppercase();
            assert_eq!(java_name.parse::<Language>(), Ok(language));
        }
    }

    #[test]
    fn position_table_round_trips_every_language() {
        let mut languages: Vec<Language> = Language::all().into_iter().collect();
        languages.sort();
        let detector = detector_of(&languages);
        for (index, language) in languages.iter().enumerate() {
            assert_eq!(detector.position[*language as usize] as usize, index);
        }
    }

    #[test]
    fn confidences_follow_creation_order_and_sum_to_one() {
        // Deliberately not alphabetical: proves the reordering is real.
        let languages = vec![Language::German, Language::English, Language::French];
        let detector = detector_of(&languages);
        let values = detector.confidences_in_creation_order(
            "The quick brown fox jumps over the lazy dog".to_owned(),
        );

        assert_eq!(values.len(), 3);
        assert!((values.iter().sum::<f64>() - 1.0).abs() < 1e-9);
        // English sits at index 1 and must win.
        assert!(values[1] > values[0] && values[1] > values[2]);
    }

    #[test]
    fn confidences_are_all_zero_for_text_without_letters() {
        let detector = detector_of(&[Language::English, Language::French]);
        let values = detector.confidences_in_creation_order("123 !!!".to_owned());
        assert_eq!(values, vec![0.0, 0.0]);
    }
}
