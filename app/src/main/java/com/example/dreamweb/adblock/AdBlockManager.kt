package com.example.dreamweb.adblock

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

class AdBlockManager(private val context: Context) {
    private val bloomFilter = BloomFilter()

    fun init() {
        loadFilters("easylist.txt")
        loadFilters("easyprivacy.txt")
    }

    private fun loadFilters(fileName: String) {
        try {
            val inputStream = context.assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotBlank() && !line.startsWith("!")) {
                    // Very basic parsing: just add the host/part of URL
                    bloomFilter.add(line.trim())
                }
                line = reader.readLine()
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun shouldBlock(url: String): Boolean {
        val host = getHost(url)
        return bloomFilter.mightContain(host)
    }

    private fun getHost(url: String): String {
        return try {
            val uri = java.net.URI(url)
            uri.host ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
