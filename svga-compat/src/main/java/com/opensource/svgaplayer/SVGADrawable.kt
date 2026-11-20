// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer

import dev.svganext.player.SVGADrawable as CoreDrawable
import dev.svganext.player.SVGAVideoEntity as CoreEntity
import dev.svganext.player.SVGADynamicEntity as CoreDynamicEntity

open class SVGADrawable : CoreDrawable {
    constructor(videoItem: CoreEntity) : super(videoItem)
    constructor(videoItem: CoreEntity, dynamicItem: CoreDynamicEntity) : super(videoItem, dynamicItem)
}
