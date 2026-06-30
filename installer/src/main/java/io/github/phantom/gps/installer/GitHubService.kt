package io.github.phantom.gps.installer

import retrofit2.http.GET

interface GitHubService {
    @GET("releases/latest")
    suspend fun getLatestRelease(): GitHubRelease
}
