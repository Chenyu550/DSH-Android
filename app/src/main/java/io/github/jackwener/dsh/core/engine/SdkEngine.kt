package io.github.jackwener.dsh.core.engine

/**
 * A dsh SDK runtime the app drives with the same JSON-RPC client ([JsonRpcPeer], ENGINE-CONTRACT §2): the
 * embedded engine ([EngineManager]) or a computer's dsh over SSH (core/remote/RemoteEngine, #51). Every
 * conversation belongs to exactly one; SessionStore routes its prompts, notifications and losses by it.
 */
interface SdkEngine {
    /**
     * The embedded engine's own SDK methods exist (`android/session/permission|mode|model|title`, account,
     * plugin-guard). A remote dsh is stock dsh: none of them, and no device tools.
     */
    val androidExtensions: Boolean
    /** Incremented on every start; a session is resumed once per generation. */
    val generation: Int
    /** Highest generation torn down: whatever a retired generation still delivers is stale. */
    val retiredGeneration: Int
    /** The running runtime's channel, without starting one (null while stopped or starting). */
    fun sdkIfReady(): JsonRpcPeer?
    /** A session is about to be opened in the current generation. */
    fun markSessionOpened()
    fun cancelIdleShutdown()
    /** Nothing of this runtime runs any more: stop it after its idle time. */
    fun scheduleIdleShutdown()
    /** Hard stop (the SDK has no per-session cancel): ends every run of this runtime. */
    suspend fun stop()
}
