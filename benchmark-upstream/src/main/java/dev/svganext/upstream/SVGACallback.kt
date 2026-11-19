package dev.svganext.upstream

/**
 * Created by cuiminghui on 2017/3/30.
 */
interface SVGACallback {

    fun onPause()
    fun onFinished()
    fun onRepeat()
    fun onStep(frame: Int, percentage: Double)

}