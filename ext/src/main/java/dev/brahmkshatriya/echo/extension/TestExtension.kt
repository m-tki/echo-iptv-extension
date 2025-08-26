package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.SettingsChangeListenerClient
import dev.brahmkshatriya.echo.common.clients.TrackerClient
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.NetworkConnection
import dev.brahmkshatriya.echo.common.models.TrackDetails
import dev.brahmkshatriya.echo.common.providers.GlobalSettingsProvider
import dev.brahmkshatriya.echo.common.providers.NetworkConnectionProvider
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingMultipleChoice
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

@Serializable
data class Country(
    val name: String,
    val code: String,
)

@Serializable
data class Category(
    val id: String,
    val name: String,
)

@Serializable
data class Channel(
    val id: String,
    val name: String,
    val owners: List<String>,
    val country: String,
    val categories: List<String>,
    @SerialName("is_nsfw")
    val isNsfw: Boolean,
)

@Serializable
data class Stream(
    val channel: String? = null,
    val url: String,
    val quality: String? = null,
)

@Serializable
data class Logo(
    val channel: String,
    val url: String,
)

data class Playlist(
    val name: String,
    val link: String,
    var enabled: Boolean,
)

class TestExtension() : ExtensionClient, HomeFeedClient, TrackClient, SearchFeedClient,
    GlobalSettingsProvider, NetworkConnectionProvider, TrackerClient, SettingsChangeListenerClient {
    override suspend fun onInitialize() {
        enableDefinedPlaylists()
        loadAdditionalPlaylists()
        if (setting.getBoolean("countries_initialized") == null)  {
            val countries = getPlaylist("${iptvOrgId}_countries", iptvOrgCountriesLink, true)
            if (countries.isNotEmpty()) {
                setting.putString("countries_serialized", countries)
                setting.putBoolean("countries_initialized", true)
            }
        }
    }

    private fun countriesNames(): List<String> {
        val countries = setting.getString("countries_serialized")
        if (countries != null && countries.isNotEmpty())
            return countries.toData<List<Country>>().map { it.name }
        else
            return emptyList()
    }

    private fun countriesCodes(): List<String> {
        val countries = setting.getString("countries_serialized")
        if (countries != null && countries.isNotEmpty())
            return countries.toData<List<Country>>().map { it.code }
        else
            return emptyList()
    }

    override suspend fun getSettingItems() = listOf(
        SettingList(
            "Default Country for IPTV-org",
            "default_country_code",
            "Select a default country to be displayed as the first tab on the home page for IPTV-org",
            countriesNames(),
            countriesCodes()
        ),
        SettingSwitch(
            "Restore Selected Server",
            "last_selected_server",
            "Whether to restore last selected server of running stream",
            lastSelectedServer
        ),
        SettingSwitch(
            "Fallback to cached playlist",
            "fallback_cached_playlist",
            "Fallback to cached playlist on failed connection or invalid data",
            fallbackCachedPlaylist
        ),
        SettingList(
            "Auto-Refresh Playlists",
            "refresh_playlists",
            "Download the latest version of playlists after a set period",
            listOf("Always", "1 Day", "3 Days", "1 Week", "1 Month", "Never (No Updates)"),
            listOf("always", "one_day", "three_days", "one_week", "one_month", "never"),
            3
        ),
        SettingTextInput(
            "Add Playlists",
            "add_playlists",
            "Add playlists in M3U/M3U8 format. To add a specific name to the playlist, " +
                    "separate the name and playlist link with a comma as in name,link also multiple" +
                    "playlists can be added separated by semicolons as in name1,link1;name2,link2"
        ),
        SettingMultipleChoice(
            "Remove Playlists",
            "remove_playlists",
            "Remove playlists from added playlists",
            additionalPlaylists.map { it.name },
            List(additionalPlaylists.size) { it.toString() }
        ),
        SettingMultipleChoice(
            "Disable Playlists",
            "disable_playlists",
            "Disable playlists in home page and search results",
            listOf(iptvOrgName) + definedPlaylists.map { it.name } + additionalPlaylists.map { it.name },
            List(definedPlaylists.size + additionalPlaylists.size + 1) { it.toString() },
            getIndicesOfDisabledPlaylists().toSet()
        )
    )

    private val defaultCountryCode get() = setting.getString("default_country_code")
    private val lastSelectedServer get() = setting.getBoolean("last_selected_server") ?: true
    private val fallbackCachedPlaylist get() = setting.getBoolean("fallback_cached_playlist") ?: true
    private val refreshPlaylists get() = setting.getString("refresh_playlists") ?: "one_week"

    private lateinit var setting: Settings
    private lateinit var globalSetting: Settings
    private lateinit var currentNetworkConnection: NetworkConnection
    override fun setSettings(settings: Settings) {
        setting = settings
    }
    override fun setGlobalSettings(globalSettings: Settings) {
        globalSetting = globalSettings
    }
    override fun setNetworkConnection(networkConnection: NetworkConnection) {
        currentNetworkConnection = networkConnection
    }

    private val streamQuality = "stream_quality"
    private val unmeteredStreamQuality = "unmetered_stream_quality"
    private val streamQualities = arrayOf("highest", "medium", "lowest")

    private var trackQuality: Int? = null

    private val iptvOrgId = "iptv_org"
    private val iptvOrgName = "IPTV-org"
    private var iptvOrgEnabled = true

    private val iptvOrgChannelsLink = "https://iptv-org.github.io/api/channels.json"
    private val iptvOrgStreamsLink = "https://iptv-org.github.io/api/streams.json"
    private val iptvOrgCountriesLink = "https://iptv-org.github.io/api/countries.json"
    private val iptvOrgCategoriesLink = "https://iptv-org.github.io/api/categories.json"
    private val iptvOrgLogosLink = "https://iptv-org.github.io/api/logos.json"

    private val definedPlaylists = listOf(
        Playlist ("PlexTV", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/PlexTV.m3u8", true),
        Playlist ("PlutoTV", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/PlutoTV.m3u8", true),
        Playlist ("TubiTV", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/TubiTV.m3u8", true),
        Playlist ("UDPTV", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/UDPTV.m3u", true),
        Playlist ("Roku", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/Roku.m3u8", true),
        Playlist ("LGTV", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/LGTV.m3u8", true),
        Playlist ("AriaPlus", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/AriaPlus.m3u8", true),
        Playlist ("SamsungTVPlus", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/SamsungTVPlus.m3u8", true),
        Playlist ("Xumo", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/Xumo.m3u8", true),
        Playlist ("StreamEast", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/StreamEast.m3u8", true),
        Playlist ("FSTV24", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/FSTV24.m3u8", true),
        Playlist ("PPVLand", "http://drewlive24.duckdns.org:8081/PPVLand.m3u8", true),
        Playlist ("Tims247", "http://drewlive24.duckdns.org:8081/Tims247.m3u8", true),
        Playlist ("Zuzz", "http://drewlive24.duckdns.org:8081/Zuzz.m3u8", true),
        Playlist ("Stirr", "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/refs/heads/main/playlists/stirr_all.m3u", true),
        Playlist ("JapanTV", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/JapanTV.m3u8", true),
        Playlist ("Local Now", "https://www.apsattv.com/localnow.m3u", true),
        Playlist ("DrewLive", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/DrewAll.m3u8", true),
        Playlist ("TVPass", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/TVPass.m3u", true),
        Playlist ("TheTVApp", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/TheTVApp.m3u8", true),
        Playlist ("DaddyLive", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/DaddyLive.m3u8", true),
        Playlist ("DaddyLiveEvents", "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/DaddyLiveEvents.m3u8", true),
    )
    private val additionalPlaylists = emptyList<Playlist>().toMutableList()
    private val getPlaylists get() = definedPlaylists + additionalPlaylists

    private val client by lazy { OkHttpClient.Builder().build() }
    private suspend fun call(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(url).build()
        ).await().body.string()
    }

    private val json by lazy { Json { ignoreUnknownKeys = true } }
    private inline fun <reified T> String.toData() =
        runCatching { json.decodeFromString<T>(this) }.getOrElse {
            throw IllegalStateException("Failed to parse JSON: $this", it)
        }

    private fun storePlaylist(id: String, playlist: String, currentTimeMillis: Long?): String {
        val currentTime = if (currentTimeMillis == null &&
            fallbackCachedPlaylist &&
            setting.getString("${id}_download_time") == null) System.currentTimeMillis()
            else currentTimeMillis
        if (currentTime != null) {
            setting.putString("${id}_content", playlist)
            setting.putString("${id}_download_time", currentTime.toString())
        }
        return playlist
    }

    private suspend fun downloadPlaylist(id: String, link: String, currentTimeMillis: Long?, emptyIfInvalid: Boolean): String {
        return try {
            val playlist = call(link)
            if (id.endsWith("playlist")) {
                if (StreamFormatByContent.isHlsByContent(playlist))
                    storePlaylist(id, playlist, currentTimeMillis)
                else if (fallbackCachedPlaylist && setting.getString("${id}_download_time") != null)
                    setting.getString("${id}_content")!!
                else if (emptyIfInvalid) ""
                else playlist
            }
            else {
                if (StreamFormatByContent.isJsonByContent(playlist))
                    storePlaylist(id, playlist, currentTimeMillis)
                else if (fallbackCachedPlaylist && setting.getString("${id}_download_time") != null)
                    setting.getString("${id}_content")!!
                else if (emptyIfInvalid) ""
                else playlist
            }
        } catch (e: Exception) {
            if (emptyIfInvalid) ""
            else throw e
        }
    }

    private suspend fun getPlaylist(id: String, link: String, days: Long, emptyIfInvalid: Boolean): String {
        val currentTimeMillis = System.currentTimeMillis()
        val downloadTime = setting.getString("${id}_download_time")
        val downloadTimeMillis = downloadTime?.toLongOrNull()
        return if (downloadTimeMillis != null) {
            val elapsedMillis = currentTimeMillis - downloadTimeMillis
            val elapsedDays = elapsedMillis / (1000 * 60 * 60 * 24)
            if (elapsedDays >= days) {
                downloadPlaylist(id, link, currentTimeMillis, emptyIfInvalid)
            }
            else {
                val storedPlaylist = setting.getString("${id}_content")
                storedPlaylist ?: downloadPlaylist(id, link, currentTimeMillis, emptyIfInvalid)
            }
        }
        else {
            downloadPlaylist(id, link, currentTimeMillis, emptyIfInvalid)
        }
    }

    private suspend fun getPlaylistNoTimeCheck(id: String, link: String, emptyIfInvalid: Boolean): String {
        val storedPlaylist = setting.getString("${id}_content")
        return storedPlaylist
            ?: downloadPlaylist(id, link, System.currentTimeMillis(), emptyIfInvalid)
    }

    private suspend fun getPlaylist(id: String, link: String, emptyIfInvalid: Boolean): String {
        return when (refreshPlaylists) {
            "one_day" -> getPlaylist(id, link, 1, emptyIfInvalid)
            "three_days" -> getPlaylist(id, link, 3, emptyIfInvalid)
            "one_week" -> getPlaylist(id, link, 7, emptyIfInvalid)
            "one_month" -> getPlaylist(id, link, 30, emptyIfInvalid)
            "never" -> getPlaylistNoTimeCheck(id, link, emptyIfInvalid)
            else -> downloadPlaylist(id, link, null, emptyIfInvalid)
        }
    }

    override suspend fun onSettingsChanged(
        settings: Settings,
        key: String?
    ) {
        when (key) {
            "add_playlists" -> addPlaylists()
            "remove_playlists" -> removeAdditionalPlaylists()
            "disable_playlists" -> disablePlaylists()
        }
    }

    private fun playlistNameToId(name: String) =
        name.lowercase().replace(" ", "_")

    private fun addPlaylist(name: String, link: String) {
        val playlistsFromSetting = setting.getString("additional_playlists").orEmpty()
        val playlistsFromSettingAppend = if (playlistsFromSetting.isEmpty()) ""
            else "$playlistsFromSetting;"
        setting.putString("additional_playlists",
            "$playlistsFromSettingAppend$name,$link,1")
        additionalPlaylists.add(Playlist(name, link, true))
    }

    private fun addPlaylists() {
        val playlists = setting.getString("add_playlists")
        if (playlists != null) {
            setting.putString("add_playlists", null)
            var playlistNumber = definedPlaylists.size + additionalPlaylists.size + 2
            val splitPlaylists = playlists.trim().split(';')
            for (playlist in splitPlaylists) {
                val splitPlaylist = playlist.trim().split(',')
                when (splitPlaylist.size) {
                    1 -> addPlaylist("Playlist $playlistNumber", splitPlaylist[0].trim())
                    2 -> addPlaylist(splitPlaylist[0].trim(), splitPlaylist[1].trim())
                }
                playlistNumber++
            }
        }
    }

    private fun removeAdditionalPlaylists() {
        val toRemovePlaylists = setting.getStringSet("remove_playlists")
        if (toRemovePlaylists != null) {
            val playlists = setting.getString("additional_playlists").orEmpty()
            if (playlists.isNotEmpty()) {
                val toRemoveIndices = toRemovePlaylists.mapNotNull { it.toIntOrNull() }
                toRemoveIndices.forEach {
                    if (it in additionalPlaylists.indices) {
                        val removedPlaylist = additionalPlaylists.removeAt(it)
                        setting.putString("${playlistNameToId(removedPlaylist.name)}_playlist", null)
                        setting.putString("${playlistNameToId(removedPlaylist.name)}_download_time", null)
                    }
                }
                val newPlaylists = additionalPlaylists.joinToString(";") {
                    "${it.name},${it.link},${if (it.enabled) "1" else "0"}"
                }
                setting.putString("additional_playlists", newPlaylists)
            }
            setting.putStringSet("remove_playlists", null)
        }
    }

    fun String.toBool(): Boolean {
        return when (this) {
            "1" -> true
            "0" -> false
            else -> throw IllegalArgumentException("String must be '1' or '0'")
        }
    }

    private fun loadAdditionalPlaylists() {
        val playlists = setting.getString("additional_playlists").orEmpty()
        if (playlists.isNotEmpty()) {
            val splitPlaylists = playlists.split(';')
            for (playlist in splitPlaylists) {
                val splitPlaylist = playlist.split(',')
                additionalPlaylists.add(Playlist(splitPlaylist[0], splitPlaylist[1], splitPlaylist[2].toBool()))
            }
        }
    }

    private fun enableDefinedPlaylists() {
        val enabledPlaylists = setting.getString("enabled_playlists")
        if (enabledPlaylists != null) {
            val splitEnabledPlaylists = enabledPlaylists.split(';')
            if (splitEnabledPlaylists.size == definedPlaylists.size + 1) {
                iptvOrgEnabled = splitEnabledPlaylists[0].toBool()
                definedPlaylists.forEachIndexed { index, _ ->
                    definedPlaylists[index].enabled = splitEnabledPlaylists[index + 1].toBool()
                }
            }
        }
    }

    private fun putEnabledPlaylists() {
        val enabledPlaylists = definedPlaylists
            .joinToString(";", if (iptvOrgEnabled) "1;" else "0;")
            { if (it.enabled) "1" else "0" }
        setting.putString("enabled_playlists", enabledPlaylists)
        val modifiedPlaylists = additionalPlaylists.joinToString(";") {
            "${it.name},${it.link},${if (it.enabled) "1" else "0"}"
        }
        setting.putString("additional_playlists", modifiedPlaylists)
    }

    private fun getIndicesOfDisabledPlaylists(): List<Int> {
        return listOfNotNull(if (!iptvOrgEnabled) 0 else null) +
                definedPlaylists.mapIndexedNotNull { index, playlist ->
                    if (!playlist.enabled) index + 1 else null } +
                definedPlaylists.mapIndexedNotNull { index, playlist ->
                    if (!playlist.enabled) index + definedPlaylists.size + 1 else null }
    }

    private fun disablePlaylists() {
        val playlistsToDisable = setting.getStringSet("disable_playlists")
        if (playlistsToDisable != null) {
            iptvOrgEnabled = "0" !in playlistsToDisable
            definedPlaylists
                .forEachIndexed { index, _ ->
                    definedPlaylists[index].enabled = (index + 1).toString() !in playlistsToDisable }
            additionalPlaylists
                .forEachIndexed { index, _ ->
                    additionalPlaylists[index].enabled = (index + definedPlaylists.size + 1).toString() !in playlistsToDisable }
            putEnabledPlaylists()
            setting.putStringSet("disable_playlists", null)
        }
    }


    private fun titleToId(title: String) =
        title.lowercase().replace(" ", "_").replace(",", "_")

    private fun createStream(playlistId: String, entry: M3U8Entry): Track {
        val trackId = "${playlistId}_${entry.tvgId.lowercase()}_${titleToId(entry.title)}"
        return Track(
            trackId,
            entry.title,
            cover = entry.tvgLogo.toImageHolder(),
            streamables = listOf(Streamable.server(entry.url, 0,
                extras = mapOf("track_id" to trackId))),
        )
    }

    private fun getStreams(playlistId: String, entries: List<M3U8Entry>): List<Shelf> {
        return entries.map {
            createStream(playlistId, it).toShelf()
        }
    }

    private fun String.toPlaylistShelf(playlistId: String): List<Shelf> {
        val parser = M3U8Parser()
        val entries = parser.parse(this)
        if (entries.any { it.groupTitle.isEmpty() } ||
            entries.map { it.groupTitle }.distinct().size == 1) {
            return getStreams(playlistId, entries)
        }
        else {
            val groups = entries.map { it.groupTitle }.distinct()
            return listOf(
                Shelf.Lists.Categories(
                    "${playlistId}_categories",
                    "Categories",
                    groups.map { group ->
                        Shelf.Category(
                            id = titleToId(group),
                            title = group,
                            PagedData.Single {
                                getStreams(playlistId, entries.filter { it.groupTitle == group })
                            }.toFeed()
                        )
                    },
                    type = Shelf.Lists.Type.Grid
                )
            )
        }
    }

    private fun getTrackId(id: String) = id.lowercase()

    private fun createTrack(channel: Channel, allStreams: List<Stream>, allLogos: List<Logo>): Track {
        return Track(
            getTrackId(channel.id),
            channel.name,
            cover = allLogos.firstOrNull { logo -> channel.id == logo.channel }?.url?.toImageHolder(),
            subtitle = channel.owners.joinToString(", "),
            streamables = allStreams.filter { ch -> ch.channel == channel.id }
                .sortedBy { if (it.quality.isNullOrEmpty()) 0u else
                    it.quality.substring(0, it.quality.length - 1).toUIntOrNull() ?: 0u }
                .mapIndexed { idx, ch -> Streamable.server(ch.url, idx, ch.quality,
                    mapOf("index" to idx.toString())) },
            isPlayable = if (allStreams.any { ch -> ch.channel == channel.id }) Track.Playable.Yes else
                Track.Playable.No("No Available Streams"),
            extras = mapOf("playlist_id" to iptvOrgId)
        )
    }

    private fun getTracks(allChannels: List<Channel>, allStreams: List<Stream>, allLogos: List<Logo>,
                          categoryId: String): List<Shelf> =
        allChannels.filter {
            it.categories.any { id -> id == categoryId } ||
                    (it.categories.isEmpty() && categoryId == "unknown")
        }.map {
            createTrack(it, allStreams, allLogos).toShelf()
        }

    private suspend fun String.toShelf(countryCode: String): List<Shelf> {
        val allCategories = getPlaylist("${iptvOrgId}_categories", iptvOrgCategoriesLink, false).toData<List<Category>>()
        val allChannels = this.toData<List<Channel>>().filter {
            !it.isNsfw && it.country == countryCode
        }
        val allCategoriesFiltered = allCategories.filter { allChannels.any { ch ->
            ch.categories.any { id -> id == it.id } } }.toMutableList()
        if (allChannels.any { it.categories.isEmpty() }) {
            allCategoriesFiltered.add(Category(name = "Unknown", id = "unknown"))
        }
        val allStreams = getPlaylist("${iptvOrgId}_streams", iptvOrgStreamsLink, false).toData<List<Stream>>()
        val allLogos = getPlaylist("${iptvOrgId}_logos", iptvOrgLogosLink, false).toData<List<Logo>>()
        return listOf(
            Shelf.Lists.Categories(
                "${iptvOrgId}_categories",
                "Categories",
                allCategoriesFiltered.map { category ->
                    Shelf.Category(
                        id = category.id,
                        title = category.name,
                        PagedData.Single {
                            getTracks(allChannels, allStreams, allLogos, category.id)
                        }.toFeed()
                    )
                },
                type = Shelf.Lists.Type.Grid
            )
        )
    }

    suspend fun iptvOrgFeed(): List<Shelf> {
        val countries = getPlaylist("${iptvOrgId}_countries", iptvOrgCountriesLink, false).toData<List<Country>>()
        val (default, others) = countries.partition { it.code == defaultCountryCode }
        return listOf(
            Shelf.Lists.Categories(
                "${iptvOrgId}_countries",
                "Countries",
                (default + others).map {
                    Shelf.Category(
                        it.code,
                        it.name,
                        PagedData.Single {
                            getPlaylist("${iptvOrgId}_channels", iptvOrgChannelsLink, false).toShelf(it.code)
                        }.toFeed()
                    )
                },
                type = Shelf.Lists.Type.Grid
            )
        )
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        addPlaylists()
        removeAdditionalPlaylists()
        disablePlaylists()
        val iptvOrgPlaylist = Shelf.Category(
            iptvOrgId,
            iptvOrgName,
            PagedData.Single {
                iptvOrgFeed()
            }.toFeed()
        ).takeIf { iptvOrgEnabled }
        return listOf(
            Shelf.Lists.Categories(
                "playlists",
                "Playlists",
                listOfNotNull(iptvOrgPlaylist) + getPlaylists.mapNotNull { playlist ->
                    Shelf.Category(
                        playlistNameToId(playlist.name),
                        playlist.name,
                        PagedData.Single {
                            getPlaylist("${playlistNameToId(playlist.name)}_playlist", playlist.link, false).toPlaylistShelf(titleToId(playlist.name))
                        }.toFeed()
                    ).takeIf { playlist.enabled }
                },
                type = Shelf.Lists.Type.Grid
            )
        ).toFeed()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val trackId = streamable.extras["track_id"]
        val streamQuality = streamable.extras["index"]
        if (lastSelectedServer && streamQuality != null) {
            trackQuality = streamQuality.toIntOrNull()
        }
        val streamType = if (trackId == null) {
            when {
                StreamFormatByExtension.isDashByExtension(streamable.id) ->
                    Streamable.SourceType.DASH
                StreamFormatByExtension.isTransportStreamByExtension(streamable.id) ->
                    Streamable.SourceType.Progressive
                else -> Streamable.SourceType.HLS
            }
        }
        else {
            when {
                StreamFormatByExtension.isHlsByExtension(streamable.id) ->
                    Streamable.SourceType.HLS
                StreamFormatByExtension.isDashByExtension(streamable.id) ->
                    Streamable.SourceType.DASH
                StreamFormatByExtension.isTransportStreamByExtension(streamable.id) ->
                    Streamable.SourceType.Progressive
                else -> {
                    val typeFromSetting = setting.getString(trackId)
                    val type = if (typeFromSetting != null) {
                        typeFromSetting
                    }
                    else {
                        val format = StreamFormat(client)
                            .detectStreamFormat(streamable.id)
                            .lowercase()
                        if (format != "error")
                            setting.putString(trackId, format)
                        format
                    }
                    when (type) {
                        "unknown" -> Streamable.SourceType.Progressive
                        "dash" -> Streamable.SourceType.DASH
                        else -> Streamable.SourceType.HLS
                    }
                }
            }
        }
        return Streamable.Media.Server(
            listOf(streamable.id.toSource(
                type = streamType,
                isVideo = true,
                isLive = true
            )),
            false
        )
    }

    private fun getStreamableQuality(idx: Int, oldSelectedIdx: Int, newSelectedIdx: Int): Int {
        return when {
            idx == oldSelectedIdx -> newSelectedIdx
            oldSelectedIdx < newSelectedIdx -> when {
                idx in (oldSelectedIdx + 1)..newSelectedIdx -> idx - 1
                else -> idx
            }
            else -> when {
                idx in newSelectedIdx until oldSelectedIdx -> idx + 1
                else -> idx
            }
        }
    }

    private fun selectQuality(track: Track, selectedQuality: Int): Track {
        val extQuality = setting.getString(streamQuality)
        val globalQuality = globalSetting.getString(streamQuality)
        val globalUnmeteredQuality = globalSetting.getString(unmeteredStreamQuality)
        val quality = if (extQuality in streamQualities) extQuality
            else if (currentNetworkConnection == NetworkConnection.Unmetered &&
                globalUnmeteredQuality in streamQualities) globalUnmeteredQuality
            else if (globalQuality in streamQualities) globalQuality
            else streamQualities[1]
        val newIdx = when (quality) {
            streamQualities[0] -> track.streamables.size - 1
            streamQualities[1] -> track.streamables.size / 2
            else -> 0
        }
        val streams = track.streamables.mapIndexed { idx, stream ->
            Streamable.server(
                stream.id,
                getStreamableQuality(idx, selectedQuality, newIdx),
                stream.title,
                stream.extras
            )
        }
        return Track(
            track.id,
            track.title,
            cover = track.cover,
            subtitle = track.subtitle,
            streamables = streams,
            isPlayable = track.isPlayable
        )
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        if (lastSelectedServer && track.extras["playlist_id"] == iptvOrgId) {
            val quality = setting.getInt("${iptvOrgId}_${getTrackId(track.id)}_quality")
            if (quality != null && quality in track.streamables.indices) {
                return selectQuality(track, quality)
            }
        }
        return track
    }

    override suspend fun onTrackChanged(details: TrackDetails?) {}

    override suspend fun onPlayingStateChanged(details: TrackDetails?, isPlaying: Boolean) {
        if (lastSelectedServer && details != null &&
            details.track.extras["playlist_id"] == iptvOrgId && isPlaying) {
            setting.putInt("${iptvOrgId}_${getTrackId(details.track.id)}_quality", trackQuality)
            trackQuality = null
        }
    }

    private suspend fun String.toIptvOrgSearch(query: String): List<EchoMediaItem> {
        val streams = getPlaylist("${iptvOrgId}_streams", iptvOrgStreamsLink, true)
        if (streams.isEmpty()) return emptyList()
        val logos = getPlaylist("${iptvOrgId}_logos", iptvOrgLogosLink, true)
        val allStreams = streams.toData<List<Stream>>()
        val allLogos = if (logos.isNotEmpty()) logos.toData<List<Logo>>()
            else emptyList()
        return this.toData<List<Channel>>().filter {
            !it.isNsfw && it.name.contains(query, true)
        }.take(100).map {
            createTrack(it, allStreams, allLogos)
        }
    }

    private fun String.toPlaylistSearch(playlistId: String,
                                        parser: M3U8Parser, query: String): List<EchoMediaItem> {
        val entries = parser.parse(this)
        return entries.filter {
            it.title.contains(query, true)
        }.take(100).map {
            createStream(playlistId, it)
        }
    }

    private suspend fun sortSearchShelf(query: String): List<Shelf> {
        val parser = M3U8Parser()
        val playlistItems = getPlaylists.mapNotNull { playlist ->
            if (!playlist.enabled) return@mapNotNull null
            val playlistContent =
                getPlaylist("${playlistNameToId(playlist.name)}_playlist", playlist.link, true)
            if (playlistContent.isEmpty()) return@mapNotNull null
            val items = playlistContent.toPlaylistSearch(titleToId(playlist.name), parser, query)
            Shelf.Lists.Items(
                "${titleToId(playlist.name)}_search",
                playlist.name,
                items.take(12),
                more = items.takeIf { items.size > 12 }?.map { it.toShelf() }?.toFeed()
            ).takeUnless { items.isEmpty() }
        }
        val iptvOrgSearch = if (iptvOrgEnabled) {
            val channels = getPlaylist("${iptvOrgId}_channels", iptvOrgChannelsLink, true)
            if (channels.isNotEmpty())
                channels.toIptvOrgSearch(query)
            else
                emptyList()
        } else {
            emptyList()
        }
        return listOfNotNull(
            Shelf.Lists.Items(
                "${iptvOrgId}_search",
                iptvOrgName,
                iptvOrgSearch.take(12),
                more = iptvOrgSearch.takeIf { iptvOrgSearch.size > 12 }?.map { it.toShelf() }?.toFeed()
            ).takeUnless { iptvOrgSearch.isEmpty() }
        ) + playlistItems
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> =
        sortSearchShelf(query).toFeed()
}
