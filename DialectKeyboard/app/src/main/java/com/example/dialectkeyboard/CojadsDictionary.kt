package com.example.dialectkeyboard

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DialectEntry(
    val reading: String,
    val word: String,
    val description: String,
    val region: String = "",
    val isDialect: Boolean = false
)

class CojadsDictionary(private val context: Context) {

    private class TrieNode {
        val children = mutableMapOf<Char, TrieNode>()
        val entries = mutableListOf<DialectEntry>()
    }

    private val root = TrieNode()

    fun loadDictionary() {
        try {
            context.assets.open("cojads_dict.tsv").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).useLines { lines ->
                    lines.drop(1).forEach { line ->
                        if (line.isNotBlank()) {
                            val tokens = line.split("\t")
                            if (tokens.size >= 5) {
                                val dialectReading  = tokens[0].trim()
                                val dialectWord     = tokens[1].trim()
                                val standardWord    = tokens[2].trim()
                                val standardReading = tokens[3].trim()
                                val region          = tokens[4].trim()

                                insert(DialectEntry(dialectReading, dialectWord, "$standardWord $region", region, isDialect = true))

                                if (standardReading.isNotEmpty() && standardReading != dialectReading) {
                                    insert(DialectEntry(standardReading, dialectWord, "$standardWord $region", region, isDialect = true))
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun insert(entry: DialectEntry) {
        var curr = root
        for (ch in entry.reading) {
            curr = curr.children.getOrPut(ch) { TrieNode() }
        }
        if (curr.entries.none { it.word == entry.word && it.description == entry.description }) {
            curr.entries.add(entry)
        }
    }

    fun searchPrefixLocal(prefix: String, limit: Int = 20): List<DialectEntry> {
        if (prefix.isEmpty()) return emptyList()
        var curr = root
        for (ch in prefix) {
            curr = curr.children[ch] ?: return emptyList()
        }

        val allResults = mutableListOf<DialectEntry>()
        collectAll(curr, allResults, limit * 3)

        val selectedPrefs = DialectRegionManager.getSelectedPrefectures(context)

        return allResults.filter { entry ->
            if (!entry.isDialect || entry.region.isEmpty()) {
                true
            } else {
                selectedPrefs.any { pref -> entry.region.contains(pref) }
            }
        }.take(limit)
    }

    private fun collectAll(node: TrieNode, results: MutableList<DialectEntry>, limit: Int) {
        if (results.size >= limit) return
        results.addAll(node.entries)
        for (child in node.children.values) {
            collectAll(child, results, limit)
            if (results.size >= limit) break
        }
    }

    suspend fun fetchGoogleCandidates(text: String): List<DialectEntry> = withContext(Dispatchers.IO) {
        if (text.isEmpty()) return@withContext emptyList()
        val results = mutableListOf<DialectEntry>()
        try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val urlString = "https://google.co.jp/transliterate?langpair=ja-Hira|ja&text=$encoded"
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 1500
            }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                for (i in 0 until jsonArray.length()) {
                    val segment = jsonArray.getJSONArray(i)
                    val reading = segment.getString(0)
                    val candidates = segment.getJSONArray(1)
                    for (j in 0 until candidates.length()) {
                        val word = candidates.getString(j)
                        results.add(DialectEntry(reading, word, "変換", isDialect = false))
                    }
                }
            }
        } catch (e: Exception) {
            // エラー時は何もしない
        }
        return@withContext results
    }
}