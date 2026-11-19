// SPDX-License-Identifier: Apache-2.0
package dev.svganext.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import dev.svganext.core.GenerationGate
import dev.svganext.core.ResourceLease
import dev.svganext.player.SVGADynamicEntity
import dev.svganext.player.R
import dev.svganext.player.SVGAImageView
import dev.svganext.player.SVGAVideoEntity

/**
 * Displays a leased animation only while attached and visible.
 *
 * A binding is remembered across detach/reattach. Call [clearRequest] when the
 * binding itself should end, for example when recycling a list item. The caller
 * owns the loader and may share it across views. Use [bind], rather than the
 * inherited XML `source` attribute, for managed loading. This view deliberately
 * ignores that attribute so every decoded entity has lease ownership.
 */
class ManagedSvgaImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SVGAImageView(context, attrs, defStyleAttr) {
    var autoPlay: Boolean = true
    var onLoaded: ((SvgaRequest) -> Unit)? = null
    var onLoadError: ((Throwable) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val deferredRelease = Runnable { if (!isEligible()) releaseDisplay() }

    private fun scheduleRelease() {
        mainHandler.removeCallbacks(deferredRelease)
        if (displayed == null) releaseDisplay()
        else if (!mainHandler.post(deferredRelease)) releaseDisplay()
    }

    private val generations = GenerationGate()
    private var binding: Binding? = null
    private var pending: CancelHandle? = null
    private var displayed: ResourceLease<SVGAVideoEntity>? = null
    private var temporarilyDetached = false

    init {
        clearsAfterStop = false
        clearsAfterDetached = false
        attrs?.let {
            val styled = context.theme.obtainStyledAttributes(it, R.styleable.SVGAImageView, 0, 0)
            try {
                autoPlay = styled.getBoolean(R.styleable.SVGAImageView_autoPlay, true)
            } finally {
                styled.recycle()
            }
        }
    }

    override fun shouldLoadSourceFromAttributes(): Boolean = false

    fun bind(loader: SvgaLoader, request: SvgaRequest, dynamic: SVGADynamicEntity = SVGADynamicEntity()) {
        checkMainThread()
        releaseDisplay()
        binding = Binding(loader, request, dynamic)
        updateVisibility()
    }

    fun clearRequest() {
        checkMainThread()
        binding = null
        releaseDisplay()
    }

    private fun updateVisibility() {
        if (binding == null) return
        if (!isEligible()) {
            scheduleRelease()
            return
        }
        mainHandler.removeCallbacks(deferredRelease)
        if (pending != null || displayed != null) return
        val selected = binding ?: return
        val generation = generations.begin()
        pending = selected.loader.load(selected.request) { result ->
            if (!generations.isCurrent(generation) || binding !== selected || !isEligible()) {
                result.getOrNull()?.close()
                return@load
            }
            pending = null
            result.fold(
                onSuccess = { lease ->
                    displayed = lease
                    try {
                        setVideoItem(lease.value, selected.dynamic)
                        if (autoPlay) startAnimation()
                        onLoaded?.invoke(selected.request)
                    } catch (error: Exception) {
                        releaseDisplay()
                        reportError(error)
                    }
                },
                onFailure = ::reportError,
            )
        }
    }

    private fun isEligible(): Boolean = !temporarilyDetached && isAttachedToWindow && isShown && windowVisibility == View.VISIBLE

    private fun releaseDisplay() {
        mainHandler.removeCallbacks(deferredRelease)
        generations.invalidate()
        pending?.cancel()
        pending = null
        stopAnimation(false)
        setImageDrawable(null)
        displayed?.close()
        displayed = null
    }

    private fun reportError(error: Throwable) {
        val listener = onLoadError
        if (listener != null) listener(error)
        else Log.w("ManagedSvgaImageView", "SVGA loading failed (${error.javaClass.simpleName})")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateVisibility()
    }

    override fun handlesDetachPlayback(): Boolean = true

    override fun onDetachedFromWindow() {
        scheduleRelease()
        clearsAfterDetached = false
        super.onDetachedFromWindow()
    }

    override fun onStartTemporaryDetach() {
        temporarilyDetached = true
        scheduleRelease()
        super.onStartTemporaryDetach()
    }

    override fun onFinishTemporaryDetach() {
        super.onFinishTemporaryDetach()
        temporarilyDetached = false
        updateVisibility()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateVisibility()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateVisibility()
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "View binding must occur on the main thread" }
    }

    private class Binding(val loader: SvgaLoader, val request: SvgaRequest, val dynamic: SVGADynamicEntity)
}
