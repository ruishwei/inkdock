package org.inkdock.reader

/** Verified upstream navigation destinations, not model download APIs. */
object OfflineVoiceCatalog {
    const val SHERPA_ENGINES = "https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html"
    const val KOKORO_GUIDE = "https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html"
    const val VOX_RELEASES = "https://github.com/CodeBySonu95/VoxSherpa-TTS/releases"
    const val PIPER_CARD = "https://huggingface.co/rhasspy/piper-voices/blob/main/zh/zh_CN/huayan/medium/MODEL_CARD"
    val urls = setOf(SHERPA_ENGINES, KOKORO_GUIDE, VOX_RELEASES, PIPER_CARD)
}
