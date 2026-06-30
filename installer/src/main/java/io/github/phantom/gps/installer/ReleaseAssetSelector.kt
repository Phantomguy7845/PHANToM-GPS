package io.github.phantom.gps.installer

object ReleaseAssetSelector {

    fun resolve(release: GitHubRelease): ReleaseInfo? {
        val asset = release.assets
            .asSequence()
            .filter { asset ->
                val name = asset.name?.lowercase() ?: return@filter false
                name.endsWith(".apk") && "full" in name && "foss" !in name
            }
            .maxByOrNull { asset -> score(asset.name.orEmpty()) }
            ?: return null

        return ReleaseInfo(
            versionTag = release.tagName.orEmpty(),
            displayName = release.name ?: release.tagName.orEmpty(),
            publishedAt = release.publishedAt.orEmpty(),
            changelog = release.body.orEmpty(),
            releaseUrl = release.htmlUrl ?: BuildConfig.GITHUB_RELEASES_PAGE,
            apkName = asset.name.orEmpty(),
            apkUrl = asset.browserDownloadUrl.orEmpty(),
        )
    }

    private fun score(name: String): Int {
        val normalized = name.lowercase()
        var score = 0
        if ("full" in normalized) score += 1000
        if ("release" in normalized) score += 200
        if ("arm64" in normalized) score += 100
        if ("universal" in normalized) score += 50
        if ("debug" in normalized) score += 10
        return score
    }
}

data class ReleaseInfo(
    val versionTag: String,
    val displayName: String,
    val publishedAt: String,
    val changelog: String,
    val releaseUrl: String,
    val apkName: String,
    val apkUrl: String,
)
