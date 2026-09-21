package ai.rever.bossterm.compose.voice.aec

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.PointerType
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Just enough CoreAudio to drive the Voice Processing I/O audio unit.
 *
 * This exists so BossTerm stops trying to cancel its own echo in Kotlin. macOS ships the same
 * acoustic echo canceller FaceTime uses, as an AudioUnit, and it has the one thing our
 * [ai.rever.bossterm.compose.voice.VoiceDuplexGate] can never have: the playback signal and the
 * captured signal inside one clock domain, sample-aligned. The gate infers echo from a level
 * comparison and a learned attenuation; VPIO subtracts it.
 *
 * JNA rather than the FFM API because this module targets Java 17, where `java.lang.foreign` is
 * still incubating, and JNA 5.19 is already a dependency here (pty4j pulls it in).
 *
 * Nothing in this file is macOS-conditional. The single call that would fail elsewhere is the
 * library load, and [CoreAudio.available] answers that without throwing, so callers can select an
 * implementation rather than catch an UnsatisfiedLinkError.
 */
internal interface AudioToolbox : Library {

    /** Find a component matching [description], starting after [inComponent] (null for first). */
    fun AudioComponentFindNext(inComponent: Pointer?, inDesc: AudioComponentDescription): Pointer?

    /** Instantiate a component. Returns an OSStatus; 0 is success. */
    fun AudioComponentInstanceNew(inComponent: Pointer, outInstance: PointerByReference): Int

    fun AudioComponentInstanceDispose(inInstance: Pointer): Int

    fun AudioUnitInitialize(inUnit: Pointer): Int

    fun AudioUnitUninitialize(inUnit: Pointer): Int

    fun AudioOutputUnitStart(inUnit: Pointer): Int

    fun AudioOutputUnitStop(inUnit: Pointer): Int

    fun AudioUnitSetProperty(
        inUnit: Pointer,
        inID: Int,
        inScope: Int,
        inElement: Int,
        inData: Pointer,
        inDataSize: Int,
    ): Int

    fun AudioUnitGetProperty(
        inUnit: Pointer,
        inID: Int,
        inScope: Int,
        inElement: Int,
        outData: Pointer,
        ioDataSize: IntByReference,
    ): Int

    /** Pull [inNumberFrames] of processed INPUT out of the unit, from inside the input callback. */
    fun AudioUnitRender(
        inUnit: Pointer,
        ioActionFlags: Pointer,
        inTimeStamp: Pointer,
        inOutputBusNumber: Int,
        inNumberFrames: Int,
        ioData: Pointer,
    ): Int
}

/** `AudioComponentDescription`, matching the C layout exactly. */
@Structure.FieldOrder("componentType", "componentSubType", "componentManufacturer", "componentFlags", "componentFlagsMask")
internal open class AudioComponentDescription : Structure() {
    @JvmField var componentType: Int = 0
    @JvmField var componentSubType: Int = 0
    @JvmField var componentManufacturer: Int = 0
    @JvmField var componentFlags: Int = 0
    @JvmField var componentFlagsMask: Int = 0
}

/**
 * `AudioStreamBasicDescription`. Field order and types are load-bearing: this struct is written
 * straight into the audio unit, and a mismatch is accepted silently and then produces noise.
 */
@Structure.FieldOrder(
    "mSampleRate", "mFormatID", "mFormatFlags", "mBytesPerPacket", "mFramesPerPacket",
    "mBytesPerFrame", "mChannelsPerFrame", "mBitsPerChannel", "mReserved",
)
internal open class AudioStreamBasicDescription : Structure() {
    @JvmField var mSampleRate: Double = 0.0
    @JvmField var mFormatID: Int = 0
    @JvmField var mFormatFlags: Int = 0
    @JvmField var mBytesPerPacket: Int = 0
    @JvmField var mFramesPerPacket: Int = 0
    @JvmField var mBytesPerFrame: Int = 0
    @JvmField var mChannelsPerFrame: Int = 0
    @JvmField var mBitsPerChannel: Int = 0
    @JvmField var mReserved: Int = 0
}

/** `AURenderCallbackStruct`: a function pointer plus its user data. */
@Structure.FieldOrder("inputProc", "inputProcRefCon")
internal open class AURenderCallbackStruct : Structure() {
    @JvmField var inputProc: AURenderCallback? = null
    @JvmField var inputProcRefCon: Pointer? = null
}

/**
 * The render/input callback.
 *
 * Called on CoreAudio's realtime thread. Whatever runs here must not allocate, block, or touch a
 * lock the JVM might hold, or the audio device glitches - which is the very symptom this whole
 * change exists to remove. Implementations copy bytes into a pre-allocated ring and return.
 */
internal interface AURenderCallback : Callback {
    fun invoke(
        inRefCon: Pointer?,
        ioActionFlags: Pointer?,
        inTimeStamp: Pointer?,
        inBusNumber: Int,
        inNumberFrames: Int,
        ioData: Pointer?,
    ): Int
}

/**
 * `AudioBuffer`: one channel group's bytes.
 *
 * `mData` is a raw pointer we own and reuse. Allocating one per callback would put a JNA
 * allocation on CoreAudio's realtime thread, which is exactly what must not happen there.
 */
@Structure.FieldOrder("mNumberChannels", "mDataByteSize", "mData")
internal open class AudioBuffer : Structure() {
    @JvmField var mNumberChannels: Int = 0
    @JvmField var mDataByteSize: Int = 0
    @JvmField var mData: Pointer? = null
}

/**
 * `AudioBufferList` with a single buffer, which is all mono needs.
 *
 * The C type ends in a flexible array member; with one buffer the fixed layout below matches it
 * exactly. A second buffer would require manual layout rather than another field.
 */
@Structure.FieldOrder("mNumberBuffers", "mBuffers")
internal open class AudioBufferList : Structure() {
    @JvmField var mNumberBuffers: Int = 1
    @JvmField var mBuffers: AudioBuffer = AudioBuffer()
}

/**
 * An [AudioBufferList] bound to memory CoreAudio owns, rather than to its own.
 *
 * `Structure.useMemory` is protected, so this exposes it deliberately instead of hand-computing
 * the struct's offsets. Those offsets involve alignment padding that differs by architecture, and
 * getting one wrong reads a plausible-looking pointer from the wrong place - a crash on the audio
 * thread, not a compile error. Letting JNA do the layout keeps that correct by construction.
 */
internal class BoundAudioBufferList : AudioBufferList() {
    /** Point this struct at [pointer] and read the current field values out of it. */
    fun bind(pointer: Pointer) {
        useMemory(pointer)
        read()
    }
}

/** A retained audio unit instance. */
internal class AudioUnitRef : PointerType()

internal object CoreAudio {

    /** Four-character codes, as CoreAudio spells its constants. */
    private fun fourCC(code: String): Int {
        require(code.length == 4) { "a four-character code must be four characters: $code" }
        return (code[0].code shl 24) or (code[1].code shl 16) or (code[2].code shl 8) or code[3].code
    }

    val KAUDIO_UNIT_TYPE_OUTPUT: Int = fourCC("auou")

    /**
     * The echo canceller. `vpio` is the AudioUnit behind FaceTime's mic path: AEC, noise
     * suppression and automatic gain, applied by the OS with the render and capture streams
     * sample-aligned.
     */
    val KAUDIO_UNIT_SUBTYPE_VOICE_PROCESSING_IO: Int = fourCC("vpio")

    val KAUDIO_UNIT_MANUFACTURER_APPLE: Int = fourCC("appl")
    val KAUDIO_FORMAT_LINEAR_PCM: Int = fourCC("lpcm")

    // kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked
    const val PCM_SIGNED_PACKED: Int = 0x4 or 0x8

    const val SCOPE_GLOBAL: Int = 0
    const val SCOPE_INPUT: Int = 1
    const val SCOPE_OUTPUT: Int = 2

    /** Bus 0 is the speaker side, bus 1 the microphone side. */
    const val BUS_OUTPUT: Int = 0
    const val BUS_INPUT: Int = 1

    const val PROP_ENABLE_IO: Int = 2003
    const val PROP_STREAM_FORMAT: Int = 8
    const val PROP_SET_RENDER_CALLBACK: Int = 23
    const val PROP_SET_INPUT_CALLBACK: Int = 2005

    /** Whether the Voice Processing unit can be used in this process. */
    val available: Boolean by lazy {
        runCatching {
            System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true) && library != null
        }.getOrDefault(false)
    }

    /**
     * Loaded lazily and never rethrown.
     *
     * A failure here means "use the JavaSound path", not "the call cannot start" - the fallback is
     * a working implementation, just one without hardware echo cancellation.
     */
    val library: AudioToolbox? by lazy {
        runCatching {
            Native.load("AudioToolbox", AudioToolbox::class.java)
        }.getOrNull()
    }
}
