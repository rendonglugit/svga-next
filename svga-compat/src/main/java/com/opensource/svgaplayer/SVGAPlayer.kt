// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer

import android.content.Context
import android.util.AttributeSet

@Deprecated("This class has been deprecated from 2.4.0. We don't recommend you to use it.")
class SVGAPlayer : SVGAImageView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
}
