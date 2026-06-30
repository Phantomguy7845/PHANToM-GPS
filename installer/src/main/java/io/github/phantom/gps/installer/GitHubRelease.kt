package io.github.phantom.gps.installer

import com.google.gson.annotations.SerializedName

data class GitHubRelease(
    @SerializedName("tag_name")
    val tagName: String? = null,
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("published_at")
    val publishedAt: String? = null,
    @SerializedName("html_url")
    val htmlUrl: String? = null,
    @SerializedName("body")
    val body: String? = null,
    @SerializedName("assets")
    val assets: List<Asset> = emptyList(),
) {
    data class Asset(
        @SerializedName("name")
        val name: String? = null,
        @SerializedName("browser_download_url")
        val browserDownloadUrl: String? = null,
    )
}
