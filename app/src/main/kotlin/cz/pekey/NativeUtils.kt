package com.android.inputmethod.latin.utils

object BinaryDictionaryUtils {
    @JvmStatic external fun createEmptyDictFileNative(
        filePath: String, dictVersion: Long, locale: String,
        attributeKeys: Array<String>, attributeValues: Array<String>
    ): Boolean
    @JvmStatic external fun calcNormalizedScoreNative(before: IntArray, after: IntArray, score: Int): Float
    @JvmStatic external fun setCurrentTimeForTestNative(currentTime: Int): Int
}

/** Jen kvůli podpisu metody; pole nejsou potřeba, dokud se nevolají aktualizace slovníku. */
class WordInputEventForPersonalization