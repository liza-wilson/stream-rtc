package io.streamrtc.transport

/**
 * In-memory [RelayTransport] for client tests, mirroring [FakeControlTransport]:
 * events emitted before the client connects are queued and replayed on
 * [connect], which keeps the tests independent of the launch order of the
 * client's coroutine.
 */
class FakeRelayTransport : RelayTransport {
    val sentText: MutableList<String> = mutableListOf()
    val sentBinary: MutableList<ByteArray> = mutableListOf()

    /** Mirrors the real socket's queue depth as reported by [queuedBytes]. */
    var queued: Long = 0

    var closed: Boolean = false
        private set

    var connectCount: Int = 0
        private set

    private var onOpen: (() -> Unit)? = null
    private var onText: ((String) -> Unit)? = null
    private var onBinary: ((ByteArray) -> Unit)? = null
    private var onClosed: ((code: Int, reason: String) -> Unit)? = null
    private var active = false
    private val pending = ArrayDeque<Event>()

    override fun connect(
        onOpen: () -> Unit,
        onText: (String) -> Unit,
        onBinary: (ByteArray) -> Unit,
        onClosed: (code: Int, reason: String) -> Unit,
    ) {
        connectCount++
        closed = false
        active = true
        this.onOpen = onOpen
        this.onText = onText
        this.onBinary = onBinary
        this.onClosed = onClosed
        while (pending.isNotEmpty()) deliver(pending.removeFirst())
    }

    override fun send(text: String): Boolean {
        if (!active) return false
        sentText.add(text)
        return true
    }

    override fun send(bytes: ByteArray): Boolean {
        if (!active) return false
        sentBinary.add(bytes)
        return true
    }

    override fun queuedBytes(): Long = queued

    override fun close() {
        closed = true
        active = false
    }

    fun emitOpen() = emit(Event.Open)

    fun emitText(text: String) = emit(Event.Text(text))

    fun emitBinary(bytes: ByteArray) = emit(Event.Binary(bytes))

    fun emitClosed(code: Int, reason: String) = emit(Event.Closed(code, reason))

    private fun emit(event: Event) {
        if (active) deliver(event) else pending.addLast(event)
    }

    private fun deliver(event: Event) {
        when (event) {
            Event.Open -> onOpen?.invoke()
            is Event.Text -> onText?.invoke(event.text)
            is Event.Binary -> onBinary?.invoke(event.bytes)
            is Event.Closed -> {
                active = false
                onClosed?.invoke(event.code, event.reason)
            }
        }
    }

    private sealed interface Event {
        data object Open : Event
        data class Text(val text: String) : Event
        data class Binary(val bytes: ByteArray) : Event
        data class Closed(val code: Int, val reason: String) : Event
    }
}
