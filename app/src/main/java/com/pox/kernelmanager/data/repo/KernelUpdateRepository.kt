package com.pox.kernelmanager.data.repo

import android.content.Context
import com.pox.kernelmanager.data.AppSettings
import com.pox.kernelmanager.data.model.GithubRelease
import com.pox.kernelmanager.data.model.KernelPackageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object KernelUpdateRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private const val GITHUB_API_BASE = "https://api.github.com/repos"

    /**
     * Releases of the repository chosen in Settings.
     *
     * There used to be a hardcoded primary/fallback pair; a silent fallback to
     * a different repo made it impossible to tell where a release came from, so
     * errors now surface against the configured repository instead.
     */
    suspend fun fetchLatestReleases(): List<GithubRelease> = withContext(Dispatchers.IO) {
        val repository = AppSettings.updaterRepository.value
        val releasesJson = try {
            fetchUrl("$GITHUB_API_BASE/$repository/releases")
        } catch (e: Exception) {
            throw Exception("$repository: ${e.message}")
        }
        json.decodeFromString<List<GithubRelease>>(releasesJson)
    }

    private fun fetchUrl(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "PoxKernelManager-Android")
            .header("Accept", "application/vnd.github.v3+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${response.message}")
            return response.body?.string() ?: throw Exception("Empty response")
        }
    }

    fun parsePackagesFromRelease(release: GithubRelease, currentEdition: String): List<KernelPackageItem> {
        val items = mutableListOf<KernelPackageItem>()
        for (asset in release.assets) {
            if (!asset.name.endsWith(".zip") || asset.name.contains("latest", true)) continue

            val edition = when {
                asset.name.contains("Onyx-Sultan-APatch", true) -> "Onyx-Sultan-APatch"
                asset.name.contains("Onyx-Sultan", true) -> "Onyx-Sultan"
                asset.name.contains("Onyx-KSU-Next", true) -> "Onyx-KSU-Next"
                asset.name.contains("Onyx-APatch", true) -> "Onyx-APatch"
                asset.name.contains("Onyx", true) -> "Onyx (Clean Unrooted)"
                asset.name.contains("Granite", true) -> "Granite (LTS)"
                asset.name.contains("Obsidian", true) -> "Obsidian (iOS RAM)"
                else -> "Custom / Generic"
            }

            items.add(
                KernelPackageItem(
                    editionName = edition,
                    zipName = asset.name,
                    downloadUrl = asset.browser_download_url,
                    sizeBytes = asset.size,
                    sha256 = asset.digestSha256,
                    releaseTag = release.tag_name,
                    isRecommendedForDevice = edition.contains(currentEdition, true)
                )
            )
        }
        return items
    }

    fun downloadPackageWithProgress(
        downloadUrl: String,
        destinationFile: File
    ): Flow<DownloadProgress> = flow {
        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "PoxKernelManager-Android")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Download failed with HTTP ${response.code}")
            val body = response.body ?: throw Exception("Empty download body")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            destinationFile.parentFile?.mkdirs()
            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(destinationFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var lastEmitTime = System.currentTimeMillis()
            var bytesSinceLastEmit = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                bytesSinceLastEmit += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastEmitTime >= 300 || downloadedBytes == totalBytes) {
                    val durationSec = (now - lastEmitTime) / 1000f
                    val speedKbps = if (durationSec > 0) (bytesSinceLastEmit / 1024f) / durationSec else 0f
                    val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0

                    emit(
                        DownloadProgress(
                            percent = percent,
                            downloadedMb = downloadedBytes / (1024f * 1024f),
                            totalMb = totalBytes / (1024f * 1024f),
                            speedKbps = speedKbps
                        )
                    )
                    lastEmitTime = now
                    bytesSinceLastEmit = 0L
                }
            }
            outputStream.flush()
            outputStream.close()
            inputStream.close()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun verifySha256(file: File, expectedHash: String): Boolean = withContext(Dispatchers.IO) {
        if (expectedHash.isEmpty()) return@withContext true
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            hex.equals(expectedHash, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }
}

data class DownloadProgress(
    val percent: Int,
    val downloadedMb: Float,
    val totalMb: Float,
    val speedKbps: Float
)
