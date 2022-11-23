package com.lagradost.cloudstream3.syncproviders.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.AcraApplication.Companion.context
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.removeKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.AuthAPI
import com.lagradost.cloudstream3.syncproviders.InAppAuthAPI
import com.lagradost.cloudstream3.syncproviders.InAppAuthAPIManager
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.BackupUtils.getBackup
import com.lagradost.cloudstream3.utils.BackupUtils.restorePromptGithub
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody


class GithubApi(index: Int) : InAppAuthAPIManager(index){
    override val idPrefix = "Github"
    override val name = "Github"
    override val icon = R.drawable.ic_github_logo
    override val requiresPassword = true
    override val createAccountUrl = "https://github.com/settings/tokens/new?description=Cloudstream+Backup&scopes=gist"

    data class GithubOAuthEntity(
        var gistId: String,
        var token: String,
        var userName: String,
        var userAvatar: String,
    )

    companion object {
        const val GITHUB_USER_KEY: String = "github_user" // user data like profile
        var currentSession: GithubOAuthEntity? = null
    }

    private fun getAuthKey(): GithubOAuthEntity? {
        return getKey(accountId, GITHUB_USER_KEY)
    }

    data class GistsElements (
        @JsonProperty("id") val gistId:String,
        @JsonProperty("files") val files: Map<String, File>,
        @JsonProperty("owner") val owner: OwnerData
    )
    data class OwnerData(
        @JsonProperty("login") val userName: String,
        @JsonProperty("avatar_url") val userAvatar : String
    )
    data class File (
        @JsonProperty("content") val dataRaw: String?
    )

    data class GistRequestBody(
        @JsonProperty("description") val description: String,
        @JsonProperty("public") val public : Boolean,
        @JsonProperty("files") val files: FilesGist?
    )
    data class FilesGist(
        @JsonProperty("Cloudstream_Backup_data.txt") val description: ContentFilesGist?,
    )
    data class ContentFilesGist(
        @JsonProperty("content") val description: String?,
    )

    private suspend fun initLogin(githubToken: String): Boolean{
        val response = app.get("https://api.github.com/gists",
            headers= mapOf(
                Pair("Accept" , "application/vnd.github+json"),
                Pair("Authorization", "token $githubToken"),
            )
        )

        if (!response.isSuccessful) { return false }

        val repo = tryParseJson<List<GistsElements>>(response.text)?.filter {
            it.files.keys.first() == "Cloudstream_Backup_data.txt"
        }

        if (repo?.isEmpty() == true){
            val backupData = context?.getBackup()
            val gitResponse = app.post("https://api.github.com/gists",
                headers= mapOf(
                    Pair("Accept" , "application/vnd.github+json"),
                    Pair("Authorization", "token $githubToken"),
                ),
                requestBody = GistRequestBody("Cloudstream private backup gist", false, FilesGist(ContentFilesGist(backupData?.toJson()))).toJson().toRequestBody(
                    RequestBodyTypes.JSON.toMediaTypeOrNull()))

            if (!gitResponse.isSuccessful) {return false}
            tryParseJson<GistsElements>(gitResponse.text).let {
                setKey(accountId, GITHUB_USER_KEY, GithubOAuthEntity(
                    token = githubToken,
                    gistId = it?.gistId?: run {
                        return false
                    },
                    userName = it.owner.userName,
                    userAvatar = it.owner.userAvatar
                ))
            }
            return true
        }
        else{
            repo?.first().let {
                setKey(accountId, GITHUB_USER_KEY, GithubOAuthEntity(
                    token = githubToken,
                    gistId = it?.gistId?: run {
                        return false
                    },
                    userName = it.owner.userName,
                    userAvatar = it.owner.userAvatar
                ))
                ioSafe  {
                    context?.restorePromptGithub()
                }
                return true
            }
        }

    }
    override suspend fun login(data: InAppAuthAPI.LoginData): Boolean {
        switchToNewAccount()
        val githubToken = data.password ?: throw IllegalArgumentException ("Requires Password")
        try {
            if (initLogin(githubToken)) {
                registerAccount()
                return true
            }
        } catch (e: Exception) {
            logError(e)
        }
        switchToOldAccount()
        return false
    }

    override fun getLatestLoginData(): InAppAuthAPI.LoginData? {
        val current = getAuthKey() ?: return null
        return InAppAuthAPI.LoginData(server = current.gistId, password = current.token, username = current.userName)
    }
    override suspend fun initialize() {
        currentSession = getAuthKey()
        val gistId = currentSession?.gistId ?: return
        val token = currentSession?.token ?: return
        setKey(gistId, token)
    }
    override fun logOut() {
        removeKey(accountId, GITHUB_USER_KEY)
        removeAccountKeys()
        currentSession = getAuthKey()
    }

    override fun loginInfo(): AuthAPI.LoginInfo? {
        return getAuthKey()?.let { user ->
            AuthAPI.LoginInfo(
                profilePicture = user.userAvatar,
                name = user.userName,
                accountIndex = accountIndex,
            )
        }
    }
}