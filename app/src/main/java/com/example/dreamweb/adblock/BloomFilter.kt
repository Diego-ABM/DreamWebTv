package com.example.dreamweb.adblock

import java.util.BitSet
import kotlin.math.absoluteValue

class BloomFilter(private val size: Int = 1024 * 64, private val hashFunctions: Int = 3) {
    private val bitSet = BitSet(size)

    fun add(item: String) {
        for (i in 0 until hashFunctions) {
            val hash = hash(item, i)
            bitSet.set((hash % size).absoluteValue)
        }
    }

    fun mightContain(item: String): Boolean {
        for (i in 0 until hashFunctions) {
            val hash = hash(item, i)
            if (!bitSet.get((hash % size).absoluteValue)) {
                return false
            }
        }
        return true
    }

    private fun hash(item: String, index: Int): Int {
        var h = 0
        val base = item.hashCode()
        h = base xor (index * 0x517cc1b7)
        h = h xor (h ushr 16)
        return h
    }
}
