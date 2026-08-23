package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

private const val LOG_TAG = "LibWhisper"

/**
 * Garde le Job du propriétaire lors du passage sur le thread natif.
 * Un CoroutineScope avec son propre Job rendrait l'appel indépendant de la
 * session et pourrait démarrer JNI après une annulation déjà demandée.
 */
internal object CallerOwnedNativeCall {
    suspend fun <T> run(
        dispatcher: CoroutineDispatcher,
        block: suspend () -> T,
    ): T = withContext(dispatcher) {
        currentCoroutineContext().ensureActive()
        block()
    }
}

class WhisperContext private constructor(private var ptr: Long) {
    // Meet Whisper C++ constraint: Don't access from more than one thread at a time.
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val released = AtomicBoolean(false)

    suspend fun transcribeData(data: FloatArray, dataTime: Long): String =
        CallerOwnedNativeCall.run(dispatcher) {
            require(ptr != 0L)
            val numThreads = WhisperCpuConfig.preferredThreadCount
            Log.d(LOG_TAG, "Selecting $numThreads threads")

            val audioCtx =
                // 1500 // setting it lower than this increases speed at the potential cost of accuracy
                min(((((dataTime.toFloat() / 1000f) / 30f) * 1500f) + 512f).toInt(), 1500)

            // Dernière instruction suspend-aware avant l'appel JNI bloquant.
            currentCoroutineContext().ensureActive()
            WhisperLib.fullTranscribe(ptr, numThreads, data, audioCtx)
            val textCount = WhisperLib.getTextSegmentCount(ptr)
            buildString {
                for (i in 0 until textCount) {
                    append(WhisperLib.getTextSegment(ptr, i))
                }
            }
        }

    suspend fun benchMemory(nthreads: Int): String = CallerOwnedNativeCall.run(dispatcher) {
        currentCoroutineContext().ensureActive()
        WhisperLib.benchMemcpy(nthreads)
    }

    suspend fun benchGgmlMulMat(nthreads: Int): String = CallerOwnedNativeCall.run(dispatcher) {
        currentCoroutineContext().ensureActive()
        WhisperLib.benchGgmlMulMat(nthreads)
    }

    suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            // La libération est l'unique opération volontairement indépendante
            // du Job appelant : une annulation ne doit jamais perdre le ptr.
            withContext(NonCancellable + dispatcher) {
                if (ptr != 0L) {
                    WhisperLib.freeContext(ptr)
                    ptr = 0
                }
            }
        } finally {
            dispatcher.close()
        }
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context with path $filePath")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from input stream")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from asset $assetPath")
            }
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String {
            return WhisperLib.getSystemInfo()
        }
    }
}

private class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            var loadVfpv4 = false
            var loadV8fp16 = false
            if (isArmEabiV7a()) {
                // armeabi-v7a needs runtime detection support
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    if (cpuInfo.contains("vfpv4")) {
                        Log.d(LOG_TAG, "CPU supports vfpv4")
                        loadVfpv4 = true
                    }
                }
            } else if (isArmEabiV8a()) {
                // ARMv8.2a needs runtime detection support
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    if (cpuInfo.contains("fphp")) {
                        Log.d(LOG_TAG, "CPU supports fp16 arithmetic")
                        loadV8fp16 = true
                    }
                }
            }

            if (loadVfpv4) {
                Log.d(LOG_TAG, "Loading libwhisper_vfpv4.so")
                System.loadLibrary("whisper_vfpv4")
            } else if (loadV8fp16) {
                Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                System.loadLibrary("whisper_v8fp16_va")
            } else {
                Log.d(LOG_TAG, "Loading libwhisper.so")
                System.loadLibrary("whisper")
            }
        }

        // JNI methods
        external fun initContextFromInputStream(inputStream: InputStream): Long
        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, audioCtx: Int)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getSystemInfo(): String
        external fun benchMemcpy(nthread: Int): String
        external fun benchGgmlMulMat(nthread: Int): String
    }
}

private fun isArmEabiV7a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("armeabi-v7a")
}

private fun isArmEabiV8a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("arm64-v8a")
}

private fun cpuInfo(): String? {
    return try {
        File("/proc/cpuinfo").inputStream().bufferedReader().use {
            it.readText()
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
        null
    }
}
