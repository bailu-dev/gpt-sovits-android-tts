use anyhow::{Context, Result, anyhow, ensure};
use gpt_sovits_onnx_rs::{InferParams, LangId, PostprocessParams, TTSModel, TokenStreamOptions};
use jni::{
    JNIEnv,
    objects::{JClass, JObject, JString, JValue},
};
use std::{
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, Ordering::Relaxed},
        mpsc::{RecvTimeoutError, sync_channel},
    },
    time::Duration,
};

struct Engine {
    bundle: PathBuf,
    model: TTSModel,
    params: InferParams,
}

static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);
static ORT: OnceLock<Arc<ort::environment::Environment>> = OnceLock::new();

fn initialize_ort(library: &Path) -> Result<()> {
    if ORT.get().is_none() {
        let pool = ort::environment::GlobalThreadPoolOptions::default()
            .with_intra_threads(4)?
            .with_inter_threads(1)?
            .with_spin_control(false)?;
        ort::init_from(library)?
            .with_global_thread_pool(pool)
            .commit();
        let _ = ORT.set(ort::environment::Environment::current()?);
    }
    Ok(())
}

fn prepare(bundle: &Path, library: &Path) -> Result<()> {
    let mut cache = ENGINE.lock().unwrap_or_else(|error| error.into_inner());
    if cache.as_ref().is_some_and(|engine| engine.bundle == bundle) {
        return Ok(());
    }

    // Release the previous sessions before opening a second model in this process.
    cache.take();
    initialize_ort(library)?;

    let params = InferParams::from_bundle(bundle)?;
    let mut model = TTSModel::from_bundle_lazy_reference(bundle)?;
    ensure!(
        model.has_streaming_vits(),
        "vits.onnx must expose stream_start_frame"
    );

    let transcript = std::fs::read_to_string(bundle.join("reference.txt"))
        .context("Cannot read reference.txt")?;
    ensure!(!transcript.trim().is_empty(), "reference.txt is empty");
    model.process_reference_sync(
        bundle.join("reference.wav"),
        transcript.trim(),
        LangId::Auto,
    )?;

    *cache = Some(Engine {
        bundle: bundle.into(),
        model,
        params,
    });
    Ok(())
}

fn stream_pcm(
    engine: &mut Engine,
    text: &str,
    env: &mut JNIEnv<'_>,
    sink: &JObject<'_>,
) -> Result<()> {
    let cancelled = AtomicBool::new(false);
    let (send, receive) = sync_channel(1);
    let cancelled = &cancelled;

    std::thread::scope(|scope| {
        let worker = scope.spawn(move || {
            engine.model.synthesize_token_chunks_sync(
                text,
                engine.params.to_sampling_params(),
                LangId::Auto,
                PostprocessParams::default(),
                TokenStreamOptions {
                    block_tokens: 8,
                    vits_left_context_tokens: None,
                },
                || cancelled.load(Relaxed),
                |_, samples| {
                    let mut pcm = Vec::with_capacity(samples.len() * 2);
                    for sample in samples {
                        let value = (sample.clamp(-1.0, 1.0) * i16::MAX as f32).round() as i16;
                        pcm.extend_from_slice(&value.to_le_bytes());
                    }
                    send.send(pcm).is_ok()
                },
            )
        });

        // Android requires SynthesisCallback calls on its original synthesis thread.
        let delivery = (|| -> Result<()> {
            loop {
                if env.call_method(sink, "isCancelled", "()Z", &[])?.z()? {
                    break;
                }
                match receive.recv_timeout(Duration::from_millis(20)) {
                    Ok(pcm) => {
                        let accepted = env.with_local_frame(8, |env| {
                            let bytes = JObject::from(env.byte_array_from_slice(&pcm)?);
                            env.call_method(sink, "write", "([B)Z", &[JValue::Object(&bytes)])?
                                .z()
                        })?;
                        if !accepted {
                            break;
                        }
                    }
                    Err(RecvTimeoutError::Timeout) => {}
                    Err(RecvTimeoutError::Disconnected) => break,
                }
            }
            Ok(())
        })();
        cancelled.store(true, Relaxed);
        drop(receive);
        let inference = worker.join().map_err(|_| anyhow!("TTS worker panicked"))?;
        delivery?;
        inference?;
        Ok(())
    })
}

fn run_jni(env: &mut JNIEnv<'_>, f: impl FnOnce(&mut JNIEnv<'_>) -> Result<()>) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| f(env)));
    let message = match result {
        Ok(Ok(())) => return,
        Ok(Err(error)) => format!("{error:#}"),
        Err(_) => "The TTS engine panicked".into(),
    };
    if matches!(env.exception_check(), Ok(false)) {
        let _ = env.throw_new("java/lang/IllegalStateException", message);
    }
}

#[unsafe(no_mangle)]
extern "system" fn Java_org_bailu_gptsovits_tts_NativeEngine_prepare(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    bundle: JString<'_>,
    library: JString<'_>,
) {
    run_jni(&mut env, |env| {
        let bundle: String = env.get_string(&bundle)?.into();
        let library: String = env.get_string(&library)?.into();
        prepare(Path::new(&bundle), Path::new(&library))
    });
}

#[unsafe(no_mangle)]
extern "system" fn Java_org_bailu_gptsovits_tts_NativeEngine_stream(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    text: JString<'_>,
    sink: JObject<'_>,
) {
    run_jni(&mut env, |env| {
        let text: String = env.get_string(&text)?.into();
        let mut cache = ENGINE.lock().unwrap_or_else(|error| error.into_inner());
        let mut engine = cache.take().context("No TTS model is loaded")?;
        stream_pcm(&mut engine, &text, env, &sink)?;
        *cache = Some(engine);
        Ok(())
    });
}
