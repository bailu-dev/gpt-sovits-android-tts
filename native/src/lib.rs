use anyhow::{Context, Result, anyhow, ensure};
use gpt_sovits_onnx_rs::{InferParams, LangId, PostprocessParams, TTSModel, TokenStreamOptions};
use jni::{
    JNIEnv,
    objects::{JClass, JObject, JString, JValue},
    sys::{JNI_FALSE, jboolean},
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
    streaming: bool,
    reference: Option<(PathBuf, String)>,
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

fn prepare(
    bundle: &Path,
    library: &Path,
    reference: Option<(PathBuf, String)>,
    streaming: bool,
) -> Result<()> {
    let mut cache = ENGINE.lock().unwrap_or_else(|error| error.into_inner());
    let mut engine = match cache.take() {
        Some(engine) if engine.bundle == bundle && engine.streaming == streaming => engine,
        previous => {
            drop(previous);
            initialize_ort(library)?;
            let model = if streaming {
                TTSModel::from_bundle_lazy_reference(bundle)?
            } else {
                TTSModel::new(
                    bundle.join("vits_non_streaming.onnx"),
                    bundle.join("ssl.onnx"),
                    bundle.join("t2s_encoder.onnx"),
                    bundle.join("t2s_fs_decoder.onnx"),
                    bundle.join("t2s_s_decoder.onnx"),
                    Some(bundle.join("bert.onnx")),
                    Some(bundle.join("g2pW.onnx")),
                    Some(bundle.join("g2p_en")),
                    Some(bundle.join("sv.onnx")),
                )?
            };
            ensure!(
                model.has_streaming_vits() == streaming,
                "The selected VITS graph does not match the requested synthesis mode"
            );
            Engine {
                bundle: bundle.into(),
                streaming,
                reference: None,
                model,
                params: InferParams::from_bundle(bundle)?,
            }
        }
    };
    if reference != engine.reference {
        if let Some((path, transcript)) = &reference {
            engine
                .model
                .process_reference_sync(path, transcript, LangId::Auto)?;
        }
        engine.reference = reference;
    }
    *cache = Some(engine);
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
        let worker = scope.spawn(move || -> Result<()> {
            let emit = |samples: &[f32]| {
                let mut pcm = Vec::with_capacity(samples.len() * 2);
                for sample in samples {
                    let value = (sample.clamp(-1.0, 1.0) * i16::MAX as f32).round() as i16;
                    pcm.extend_from_slice(&value.to_le_bytes());
                }
                send.send(pcm).is_ok()
            };
            let sampling = engine.params.to_sampling_params();
            let postprocess = PostprocessParams::default();
            if engine.streaming {
                engine.model.synthesize_token_chunks_sync(
                    text,
                    sampling,
                    LangId::Auto,
                    postprocess,
                    TokenStreamOptions {
                        block_tokens: 16,
                        vits_left_context_tokens: None,
                    },
                    || cancelled.load(Relaxed),
                    |_, samples| emit(&samples),
                )?;
            } else if !cancelled.load(Relaxed) {
                let (_, samples) =
                    engine
                        .model
                        .synthesize_sync(text, sampling, LangId::Auto, postprocess)?;
                for chunk in samples.chunks(8192) {
                    if cancelled.load(Relaxed) || !emit(chunk) {
                        break;
                    }
                }
            }
            Ok(())
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
extern "system" fn Java_org_bailu_gptsovits_tts_NativeEngine_prepareModel(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    bundle: JString<'_>,
    library: JString<'_>,
    wav: JString<'_>,
    transcript: JString<'_>,
    streaming: jboolean,
) {
    run_jni(&mut env, |env| {
        let bundle: String = env.get_string(&bundle)?.into();
        let library: String = env.get_string(&library)?.into();
        let wav: String = env.get_string(&wav)?.into();
        let transcript: String = env.get_string(&transcript)?.into();
        let reference = (!wav.is_empty()).then(|| (PathBuf::from(wav), transcript));
        prepare(
            Path::new(&bundle),
            Path::new(&library),
            reference,
            streaming != JNI_FALSE,
        )
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
