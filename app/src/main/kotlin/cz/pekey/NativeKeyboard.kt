package com.android.inputmethod.keyboard

object ProximityInfo {
    @JvmStatic external fun setProximityInfoNative(
        displayWidth: Int, displayHeight: Int, gridWidth: Int, gridHeight: Int,
        mostCommonKeyWidth: Int, mostCommonKeyHeight: Int,
        proximityCharsArray: IntArray, keyCount: Int,
        keyXCoordinates: IntArray, keyYCoordinates: IntArray,
        keyWidths: IntArray, keyHeights: IntArray, keyCharCodes: IntArray,
        // null = bez korekce dotyku (jako AOSP klávesnice bez tabulky korekcí)
        sweetSpotCenterXs: FloatArray?, sweetSpotCenterYs: FloatArray?, sweetSpotRadii: FloatArray?
    ): Long

    @JvmStatic external fun releaseProximityInfoNative(nativeProximityInfo: Long)
}