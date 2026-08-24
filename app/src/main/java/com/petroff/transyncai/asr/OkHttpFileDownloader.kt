package com.petroff.transyncai.asr

import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request

class OkHttpFileDownloader(private val httpClient: OkHttpClient) : FileDownloader {
    override fun download(url: String, dest: File) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Failed to download $url: HTTP ${response.code}" }
            dest.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
        }
    }
}
