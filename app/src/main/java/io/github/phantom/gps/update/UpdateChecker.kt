package io.github.phantom.gps.update

import android.content.Context
import android.os.Parcelable
import io.github.phantom.gps.BuildConfig
import io.github.phantom.gps.utils.PrefManager
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject


class UpdateChecker @Inject constructor(private val apiResponse : GitHubService) {


    fun getLatestRelease() = callbackFlow {
        withContext(Dispatchers.IO){
            getReleaseList()?.let { gitHubReleaseResponse ->
                val currentTag = gitHubReleaseResponse.tagName

                if (currentTag != null && (currentTag != "v" + BuildConfig.TAG_NAME && PrefManager.isUpdateDisabled)) {
                    //New update available!
                    val asset = selectPreferredAsset(gitHubReleaseResponse)
                    val releaseUrl = gitHubReleaseResponse.htmlUrl ?: BuildConfig.GITHUB_RELEASES_PAGE
                    val name = gitHubReleaseResponse.name ?: run {
                        this@callbackFlow.trySend(null).isSuccess
                        return@let
                    }
                    val body = gitHubReleaseResponse.body ?: run {
                        this@callbackFlow.trySend(null).isSuccess
                        return@let
                    }
                    val publishedAt = gitHubReleaseResponse.publishedAt ?: run {
                        this@callbackFlow.trySend(null).isSuccess
                        return@let
                    }
                    this@callbackFlow.trySend(
                        Update(
                            name,
                            body,
                            publishedAt,
                            asset?.browserDownloadUrl
                                ?: BuildConfig.GITHUB_RELEASES_PAGE,
                            asset?.name ?: "app-full-arm64-v8a-debug.apk",
                            releaseUrl
                        )
                    ).isSuccess
                }
            } ?: run {
                this@callbackFlow.trySend(null).isSuccess
            }
        }
        awaitClose {  }
    }


    private fun getReleaseList(): GitHubRelease? {

        runCatching {
            apiResponse.getReleases().execute().body()
        }.onSuccess {
            return it
        }.onFailure {
            return null
        }
        return null
    }

    fun clearCachedDownloads(context: Context){
        File(context.externalCacheDir, "updates").deleteRecursively()
    }

    private fun selectPreferredAsset(release: GitHubRelease): GitHubRelease.Asset? {
        return release.assets
            ?.asSequence()
            ?.filter { asset ->
                val name = asset.name?.lowercase() ?: return@filter false
                name.endsWith(".apk") && "full" in name && "foss" !in name
            }
            ?.maxByOrNull { asset ->
                val name = asset.name.orEmpty().lowercase()
                var score = 0
                if ("full" in name) score += 1000
                if ("release" in name) score += 200
                if ("arm64" in name) score += 100
                if ("universal" in name) score += 50
                if ("debug" in name) score += 10
                score
            }
    }

    @Parcelize
    data class Update(val name: String, val changelog: String, val timestamp: String, val assetUrl: String, val assetName: String, val releaseUrl: String):
        Parcelable
}


