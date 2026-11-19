// SPDX-License-Identifier: Apache-2.0
package dev.readablesvga.core

/**
 * One owner's claim on a resource. Forking creates another claim; the final
 * close disposes the resource exactly once. Closing the same lease is harmless.
 *
 * Keep a lease open for the entire use of [value]. A returned value must not be
 * used after this lease closes, including a close from another thread.
 */
class ResourceLease<V : Any> private constructor(private val resource: SharedResource<V>) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    val value: V
        get() = synchronized(lock) {
            check(!closed) { "Resource lease is closed" }
            resource.value()
        }

    fun fork(): ResourceLease<V> = synchronized(lock) {
        check(!closed) { "Resource lease is closed" }
        resource.retain()
        ResourceLease(resource)
    }

    override fun close() {
        val release = synchronized(lock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (release) resource.release()
    }

    internal fun isSoleOwner() = resource.isSoleOwner()

    companion object {
        /** Takes exclusive ownership of a newly created, otherwise unowned resource. */
        fun <V : Any> own(value: V, dispose: (V) -> Unit): ResourceLease<V> =
            ResourceLease(SharedResource(value, dispose))
        internal fun <V : Any> observed(value: V, dispose: (V) -> Unit, onIdle: () -> Unit): ResourceLease<V> =
            ResourceLease(SharedResource(value, dispose, onIdle))
    }
}

private class SharedResource<V : Any>(value: V, private val dispose: (V) -> Unit, private val onIdle: (() -> Unit)? = null) {
    private val lock = Any()
    private var value: V? = value
    private var references = 1

    fun value(): V = synchronized(lock) {
        checkNotNull(value) { "Resource has been disposed" }
    }

    fun retain() = synchronized(lock) {
        check(references in 1 until Int.MAX_VALUE) { "Cannot retain this resource" }
        references++
    }

    fun isSoleOwner() = synchronized(lock) { references == 1 }

    fun release() {
        var becameIdle = false
        val retired = synchronized(lock) {
            check(references > 0) { "Resource reference count is invalid" }
            references--
            becameIdle = references == 1
            if (references == 0) value.also { value = null } else null
        }
        // User cleanup runs without either the lease or reference-count lock.
        retired?.let(dispose)
        if (becameIdle) onIdle?.invoke()
    }
}
