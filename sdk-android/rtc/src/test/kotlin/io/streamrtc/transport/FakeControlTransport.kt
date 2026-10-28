package io.streamrtc.transport

/**
 * In-memory [ControlTransport] for client tests. Events emitted before the
 * client connects are queued and replayed on [connect], which keeps the
 * tests independent of the launch order of the client's coroutine.
 */
class FakeControlTransport : ControlTransport {
    val sent: MutableList<String> = mutableListOf()

    var closed: Boolean = false
        private set

    var connectCount: Int = 0
        private set

    private var onOpen: (() -> Unit)? = null
    private var onMessage: ((String) -> Unit)? = null
    private var onClosed: ((code: Int, reason: String) -> Unit)? = null
    private var active = false
    private val pending = ArrayDeque<Event>()

    override fun connect(
        onOpen: () -> Unit,
        onMessage: (String) -> Unit,
        onClosed: (code: Int, reason: String) -> Unit,
    ) {
        connectCount++
        closed = false
        active = true
        this.onOpen = onOpen
        this.onMessage = onMessage
        this.onClosed = onClosed
        while (pending.isNotEmpty()) deliver(pending.removeFirst())
    }

    override fun send(text: String): Boolean {
        if (!active) return false
        sent.add(text)
        return true
    }

    override fun close() {
        closed = true
        active = false
    }

    fun emitOpen() = emit(Event.Open)

    fun emitMessage(text: String) = emit(Event.Message(text))

    fun emitClosed(code: Int, reason: String) = emit(Event.Closed(code, reason))

    private fun emit(event: Event) {
        if (active) deliver(event) else pending.addLast(event)
    }

    private fun deliver(event: Event) {
        when (event) {
            Event.Open -> onOpen?.invoke()
            is Event.Message -> onMessage?.invoke(event.text)
            is Event.Closed -> {
                active = false
                onClosed?.invoke(event.code, event.reason)
            }
        }
    }

    private sealed interface Event {
        data object Open : Event
        data class Message(val text: String) : Event
        data class Closed(val code: Int, val reason: String) : Event
    }
}
