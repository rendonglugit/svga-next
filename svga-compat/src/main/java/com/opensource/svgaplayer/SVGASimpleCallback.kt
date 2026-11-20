// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer

/**
 * Convenient implementation of [SVGACallback] with empty default methods.
 */
open class SVGASimpleCallback : SVGACallback {
    override fun onPause() {}
    override fun onFinished() {}
    override fun onRepeat() {}
    override fun onStep(frame: Int, percentage: Double) {}
}
