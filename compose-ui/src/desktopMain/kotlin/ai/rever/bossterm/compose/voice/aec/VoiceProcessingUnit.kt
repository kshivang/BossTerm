package ai.rever.bossterm.compose.voice.aec

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The macOS Voice Processing I/O audio unit, driven as a full-duplex PCM16 mono device.
 *
 * Capture comes out with the speaker signal already removed, by the OS, using the render and
 * capture streams sample-aligned inside one clock domain. That is the thing
 * [ai.rever.bossterm.compose.voice.VoiceDuplexGate] cannot do from Kotlin: it compares a level
 * against a learned attenuation, which held at 0.10-0.24 against a true coupling near 0.83 on a
 * real call and turned the agent's own voice into a barge-in on frame after frame.
 *
 * ## The realtime contract
 *
 * Both callbacks run on CoreAudio's realtime thread. They may not allocate, block, take a lock the
 * JVM might hold, or call anything that could park. Everything they touch - the rings, the
 * [AudioBufferList], the scratch [Memory] - is allocated once in [start] and only copied through
 * afterwards. A JNA callback already costs an attach and a JNI transition; adding a GC pause on
 * top is how an echo canceller ends up producing the artifacts it was installed to remove.
 *
 * Callback objects are held in fields for the unit's whole life. JNA keeps no strong reference to
 * a callback it has handed to native code, so letting one become garbage is a crash that arrives
 * minutes later, on the audio thread, with a stack that points nowhere useful.
 */
internal class VoiceProcessingUnit(
    private val sampleRate: Int = 24_000,
    /** Ring capacity in bytes. A second of audio each way absorbs a long GC pause without loss. */
    ringBytes: Int = 24_000 * 2,
) {
    private val log = LoggerFactory.getLogger(VoiceProcessingUnit::class.java)

    /** Echo-cancelled microphone audio, written by the input callback. */
    val capture = AudioRing(ringBytes)

    /** Audio destined for the speakers, read by the render callback. */
    val playback = AudioRing(ringBytes)

    private var unit: Pointer? = null
    private val running = AtomicBoolean(false)

    // Allocated once, reused by the realtime callbacks. See the class note.
    private var scratch: Memory? = null
    private var bufferList: AudioBufferList? = null
    private var silence: ByteArray = ByteArray(0)

    /** Wrapper for the speaker buffer CoreAudio supplies; see [onSpeakers]. */
    private val outgoing = BoundAudioBufferList()
    private var transfer: ByteArray = ByteArray(0)

    // Strong references so JNA cannot collect the callbacks out from under native code.
    private var inputCallback: AURenderCallback? = null
    private var renderCallback: AURenderCallback? = null

    /** Whether the unit is started and pumping audio. */
    fun isRunning(): Boolean = running.get()

    /**
     * Configure and start the unit.
     *
     * @return null on success, or a human-readable reason the caller should fall back to JavaSound.
     *   A failure here is never fatal: the fallback is a working audio path, just one without
     *   hardware echo cancellation, so this reports rather than throws.
     */
    fun start(): String? {
        if (running.get()) return null
        val toolbox = CoreAudio.library ?: return "AudioToolbox is not available on this system"

        val description = AudioComponentDescription().apply {
            componentType = CoreAudio.KAUDIO_UNIT_TYPE_OUTPUT
            componentSubType = CoreAudio.KAUDIO_UNIT_SUBTYPE_VOICE_PROCESSING_IO
            componentManufacturer = CoreAudio.KAUDIO_UNIT_MANUFACTURER_APPLE
            write()
        }
        val component = toolbox.AudioComponentFindNext(null, description)
            ?: return "this system has no Voice Processing I/O audio unit"

        val instance = PointerByReference()
        toolbox.AudioComponentInstanceNew(component, instance).let {
            if (it != 0) return "could not create the voice processing unit (OSStatus $it)"
        }
        val created = instance.value ?: return "the voice processing unit was created as null"

        val failure = configure(toolbox, created)
        if (failure != null) {
            runCatching { toolbox.AudioComponentInstanceDispose(created) }
            return failure
        }

        toolbox.AudioUnitInitialize(created).let {
            if (it != 0) {
                runCatching { toolbox.AudioComponentInstanceDispose(created) }
                return "could not initialise the voice processing unit (OSStatus $it)"
            }
        }
        toolbox.AudioOutputUnitStart(created).let {
            if (it != 0) {
                runCatching { toolbox.AudioUnitUninitialize(created) }
                runCatching { toolbox.AudioComponentInstanceDispose(created) }
                return "could not start the voice processing unit (OSStatus $it)"
            }
        }

        unit = created
        running.set(true)
        log.info("Voice Processing I/O started: PCM16 mono {} Hz, hardware echo cancellation active", sampleRate)
        return null
    }

    /** Enable both directions, pin the format, and install the callbacks. */
    private fun configure(toolbox: AudioToolbox, created: Pointer): String? {
        val on = Memory(4).apply { setInt(0, 1) }
        if (toolbox.AudioUnitSetProperty(
                created, CoreAudio.PROP_ENABLE_IO, CoreAudio.SCOPE_INPUT, CoreAudio.BUS_INPUT, on, 4,
            ) != 0
        ) {
            return "the voice processing unit refused to enable its microphone bus"
        }
        if (toolbox.AudioUnitSetProperty(
                created, CoreAudio.PROP_ENABLE_IO, CoreAudio.SCOPE_OUTPUT, CoreAudio.BUS_OUTPUT, on, 4,
            ) != 0
        ) {
            return "the voice processing unit refused to enable its speaker bus"
        }

        val format = AudioStreamBasicDescription().apply {
            mSampleRate = sampleRate.toDouble()
            mFormatID = CoreAudio.KAUDIO_FORMAT_LINEAR_PCM
            mFormatFlags = CoreAudio.PCM_SIGNED_PACKED
            mFramesPerPacket = 1
            mChannelsPerFrame = 1
            mBitsPerChannel = 16
            mBytesPerFrame = 2
            mBytesPerPacket = 2
            write()
        }
        // The format is set on the side of each bus WE touch: what the unit hands us from the
        // microphone, and what we hand it for the speakers.
        if (toolbox.AudioUnitSetProperty(
                created, CoreAudio.PROP_STREAM_FORMAT, CoreAudio.SCOPE_OUTPUT, CoreAudio.BUS_INPUT,
                format.pointer, format.size(),
            ) != 0
        ) {
            return "the voice processing unit rejected PCM16 mono on the microphone bus"
        }
        if (toolbox.AudioUnitSetProperty(
                created, CoreAudio.PROP_STREAM_FORMAT, CoreAudio.SCOPE_INPUT, CoreAudio.BUS_OUTPUT,
                format.pointer, format.size(),
            ) != 0
        ) {
            return "the voice processing unit rejected PCM16 mono on the speaker bus"
        }

        allocateRealtimeBuffers()

        val input = object : AURenderCallback {
            override fun invoke(
                inRefCon: Pointer?,
                ioActionFlags: Pointer?,
                inTimeStamp: Pointer?,
                inBusNumber: Int,
                inNumberFrames: Int,
                ioData: Pointer?,
            ): Int = onMicrophone(ioActionFlags, inTimeStamp, inBusNumber, inNumberFrames)
        }
        val render = object : AURenderCallback {
            override fun invoke(
                inRefCon: Pointer?,
                ioActionFlags: Pointer?,
                inTimeStamp: Pointer?,
                inBusNumber: Int,
                inNumberFrames: Int,
                ioData: Pointer?,
            ): Int = onSpeakers(inNumberFrames, ioData)
        }
        inputCallback = input
        renderCallback = render

        val inputStruct = AURenderCallbackStruct().apply { inputProc = input; write() }
        if (toolbox.AudioUnitSetProperty(
                created, CoreAudio.PROP_SET_INPUT_CALLBACK, CoreAudio.SCOPE_GLOBAL, CoreAudio.BUS_INPUT,
                inputStruct.pointer, inputStruct.size(),
            ) != 0
        ) {
            return "could not install the microphone callback"
        }
        val renderStruct = AURenderCallbackStruct().apply { inputProc = render; write() }
        if (toolbox.AudioUnitSetProperty(
                created, CoreAudio.PROP_SET_RENDER_CALLBACK, CoreAudio.SCOPE_INPUT, CoreAudio.BUS_OUTPUT,
                renderStruct.pointer, renderStruct.size(),
            ) != 0
        ) {
            return "could not install the speaker callback"
        }
        return null
    }

    /**
     * Everything the realtime path will touch, allocated before the device starts.
     *
     * Sized for a generous callback rather than a typical one: CoreAudio chooses the slice, it can
     * change it at runtime, and a buffer that is merely usually big enough fails as an audible
     * glitch under load rather than as an error.
     */
    private fun allocateRealtimeBuffers() {
        val maxFrames = sampleRate / 2
        val maxBytes = maxFrames * 2
        scratch = Memory(maxBytes.toLong())
        transfer = ByteArray(maxBytes)
        silence = ByteArray(maxBytes)
        bufferList = AudioBufferList().apply {
            mNumberBuffers = 1
            mBuffers.mNumberChannels = 1
            mBuffers.mDataByteSize = maxBytes
            mBuffers.mData = scratch
            write()
        }
    }

    /**
     * Microphone slice available: pull it from the unit and stash it.
     *
     * The bytes only become echo-cancelled by going through [AudioToolbox.AudioUnitRender] - the
     * callback itself carries no audio, only the notification that some exists. Rendering into our
     * own reused buffer list is what applies the processing.
     */
    private fun onMicrophone(flags: Pointer?, timestamp: Pointer?, bus: Int, frames: Int): Int {
        val active = unit ?: return 0
        val toolbox = CoreAudio.library ?: return 0
        val list = bufferList ?: return 0
        val wanted = frames * 2
        if (wanted <= 0 || wanted > transfer.size) return 0

        // Re-state the size every callback: AudioUnitRender overwrites it with what it actually
        // produced, so a previous short slice would otherwise cap this one.
        list.mBuffers.mDataByteSize = wanted
        list.writeField("mBuffers")

        val status = toolbox.AudioUnitRender(active, flags ?: Pointer.NULL, timestamp ?: Pointer.NULL, bus, frames, list.pointer)
        if (status != 0) return status

        list.read()
        val produced = list.mBuffers.mDataByteSize.coerceAtMost(transfer.size)
        if (produced <= 0) return 0
        scratch?.read(0, transfer, 0, produced)
        capture.write(transfer, 0, produced)
        return 0
    }

    /**
     * The speakers want a slice: hand over whatever is queued, padded with silence.
     *
     * Silence rather than a short buffer, because CoreAudio reads the full slice regardless and
     * an unfilled tail plays as whatever was in that memory last - a click, or a fragment of the
     * previous slice repeated, which is precisely the stutter this work set out to remove.
     */
    private fun onSpeakers(frames: Int, ioData: Pointer?): Int {
        val destination = ioData ?: return 0
        val wanted = frames * 2
        if (wanted <= 0 || wanted > transfer.size) return 0

        // Reused, not allocated: this runs on the realtime thread. It only ever wraps memory
        // CoreAudio hands in, so it holds no storage of its own.
        val out = outgoing
        out.bind(destination)
        val target = out.mBuffers.mData ?: return 0
        val capacity = out.mBuffers.mDataByteSize.coerceAtMost(wanted)

        val got = playback.read(transfer, 0, capacity)
        if (got > 0) target.write(0, transfer, 0, got)
        if (got < capacity) target.write(got.toLong(), silence, 0, capacity - got)
        return 0
    }

    /** Stop and release. Safe to call when never started. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val active = unit ?: return
        val toolbox = CoreAudio.library
        unit = null
        runCatching { toolbox?.AudioOutputUnitStop(active) }
        runCatching { toolbox?.AudioUnitUninitialize(active) }
        runCatching { toolbox?.AudioComponentInstanceDispose(active) }
        // Only after the device is stopped: releasing these while a callback could still fire
        // hands CoreAudio a pointer into freed memory.
        inputCallback = null
        renderCallback = null
        bufferList = null
        scratch = null
        log.info("Voice Processing I/O stopped")
    }
}
