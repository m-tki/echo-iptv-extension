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
class TestExtension() : ExtensionClient, HomeFeedClient, TrackClient, SearchFeedClient,
    GlobalSettingsProvider, NetworkConnectionProvider, TrackerClient, SettingsChangeListenerClient {
    override suspend fun onInitialize() {
        loadAdditionalPlaylists()
        if (setting.getBoolean("countries_initialized") == null)  {
            setting.putString("countries_serialized", call(iptvOrgCountriesLink))
            setting.putBoolean("countries_initialized", true)
        }
    }

    override suspend fun getSettingItems() = listOf(
        SettingList(
            "Default Country for IPTV-org",
            "default_country_code",
            "Select a default country to be displayed as the first tab on the home page for IPTV-org",
            setting.getString("countries_serialized")!!.toData<List<Country>>().map { it.name },
            setting.getString("countries_serialized")!!.toData<List<Country>>().map { it.code }
        ),
        SettingSwitch(
            "Restore Selected Server",
            "last_selected_server",
            "Whether to restore last selected server for streams",
            lastSelectedServer
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
            additionalPlaylists.map { it.first },
            List(additionalPlaylists.size) { it.toString() }
        )
    )

    private val defaultCountryCode get() = setting.getString("default_country_code")
    private val lastSelectedServer get() = setting.getBoolean("last_selected_server") ?: true

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

    private val iptvOrgChannelsLink = "https://iptv-org.github.io/api/channels.json"
    private val iptvOrgStreamsLink = "https://iptv-org.github.io/api/streams.json"
    private val iptvOrgCountriesLink = "https://iptv-org.github.io/api/countries.json"
    private val iptvOrgCategoriesLink = "https://iptv-org.github.io/api/categories.json"
    private val iptvOrgLogosLink = "https://iptv-org.github.io/api/logos.json"

    private val m3u8Playlists = listOf(
        "DrewLive" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/DrewAll.m3u8",
        "PlexTV" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/PlexTV.m3u8",
        "PlutoTV" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/PlutoTV.m3u8",
        "TubiTV" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/TubiTV.m3u8",
        "UDPTV" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/UDPTV.m3u",
        "Roku" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/Roku.m3u8",
        "LGTV" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/LGTV.m3u8",
        "AriaPlus" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/AriaPlus.m3u8",
        "SamsungTVPlus" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/SamsungTVPlus.m3u8",
        "Xumo" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/Xumo.m3u8",
        "StreamEast" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/StreamEast.m3u8",
        "FSTV24" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/FSTV24.m3u8",
        "PPVLand" to "http://drewlive24.duckdns.org:8081/PPVLand.m3u8",
        "Tims247" to "http://drewlive24.duckdns.org:8081/Tims247.m3u8",
        "Zuzz" to "http://drewlive24.duckdns.org:8081/Zuzz.m3u8",
        "Stirr" to "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/refs/heads/main/playlists/stirr_all.m3u",
        "JapanTV" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/JapanTV.m3u8",
        "Local Now" to "https://www.apsattv.com/localnow.m3u",
        "TVPass" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/TVPass.m3u",
        "TheTVApp" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/TheTVApp.m3u8",
        "DaddyLive" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/DaddyLive.m3u8",
        "DaddyLiveEvents" to "https://raw.githubusercontent.com/Drewski2423/DrewLive/refs/heads/main/DaddyLiveEvents.m3u8",
    )
    private val additionalPlaylists = emptyList<Pair<String, String>>().toMutableList()

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

    private fun getPlaylists() = m3u8Playlists + additionalPlaylists

    override suspend fun onSettingsChanged(
        settings: Settings,
        key: String?
    ) {
        if (key == "add_playlists")
            addPlaylists()
        else if (key == "remove_playlists")
            removeAdditionalPlaylists()
    }

    private fun addPlaylist(name: String, link: String) {
        val playlistsFromSetting = setting.getString("additional_playlists")
        val playlistsFromSettingAppend = if (playlistsFromSetting == null) ""
            else "$playlistsFromSetting;"
        setting.putString("additional_playlists",
            "$playlistsFromSettingAppend$name,$link")
        additionalPlaylists.add(name to link)
    }

    private fun addPlaylists() {
        val playlists = setting.getString("add_playlists")
        if (playlists != null) {
            setting.putString("add_playlists", null)
            var playlistNumber = m3u8Playlists.size + additionalPlaylists.size + 2
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
                toRemoveIndices.forEach { additionalPlaylists.removeAt(it) }
                val newPlaylists = playlists.split(';')
                    .filterIndexed { index, _ -> index !in toRemoveIndices }
                    .joinToString(";")
                setting.putString("additional_playlists", newPlaylists)
            }
            setting.putStringSet("remove_playlists", null)
        }
    }

    private fun loadAdditionalPlaylists() {
        val playlists = setting.getString("additional_playlists")
        if (playlists != null) {
            val splitPlaylists = playlists.split(';')
            for (playlist in splitPlaylists) {
                val splitPlaylist = playlist.split(',')
                additionalPlaylists.add(splitPlaylist[0] to splitPlaylist[1])
            }
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
        val trackId = getTrackId(channel.id)
        val isAvailable = allStreams.any { ch -> ch.channel == channel.id }
        return Track(
            trackId,
            channel.name,
            cover = allLogos.firstOrNull { logo -> channel.id == logo.channel }?.url?.toImageHolder(),
            subtitle = channel.owners.joinToString(", "),
            streamables = allStreams.filter { ch -> ch.channel == channel.id }
                .sortedBy { if (it.quality.isNullOrEmpty()) 0u else
                    it.quality.substring(0, it.quality.length - 1).toUIntOrNull() ?: 0u }
                .mapIndexed { idx, ch -> Streamable.server(ch.url, idx, ch.quality,
                    mapOf("index" to idx.toString())) },
            isPlayable = if (isAvailable) Track.Playable.Yes else
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
        val allCategories = call(iptvOrgCategoriesLink).toData<List<Category>>()
        val allChannels = this.toData<List<Channel>>().filter {
            !it.isNsfw && it.country == countryCode
        }
        val allCategoriesFiltered = allCategories.filter { allChannels.any { ch ->
            ch.categories.any { id -> id == it.id } } }.toMutableList()
        if (allChannels.any { it.categories.isEmpty() }) {
            allCategoriesFiltered.add(Category(name = "Unknown", id = "unknown"))
        }
        val allStreams = call(iptvOrgStreamsLink).toData<List<Stream>>()
        val allLogos = call(iptvOrgLogosLink).toData<List<Logo>>()
        return listOf(
            Shelf.Lists.Categories(
                "iptv_org_categories",
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
        val countries = call(iptvOrgCountriesLink).toData<List<Country>>()
        val (default, others) = countries.partition { it.code == defaultCountryCode }
        return listOf(
            Shelf.Lists.Categories(
                "iptv_org_countries",
                "Countries",
                (default + others).map {
                    Shelf.Category(
                        it.code,
                        it.name,
                        PagedData.Single {
                            call(iptvOrgChannelsLink).toShelf(it.code)
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
        val iptvOrgPlaylist = Shelf.Category(
            iptvOrgId,
            iptvOrgName,
            PagedData.Single {
                iptvOrgFeed()
            }.toFeed()
        )
        return listOf(
            Shelf.Lists.Categories(
                "playlists",
                "Playlists",
                listOf(iptvOrgPlaylist) + getPlaylists().map {
                    Shelf.Category(
                        it.first.lowercase(),
                        it.first,
                        PagedData.Single {
                            call(it.second).toPlaylistShelf(titleToId(it.first))
                        }.toFeed()
                    )
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
            Streamable.SourceType.HLS
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
            val quality = setting.getInt("${iptvOrgId}_${track.id}_quality")
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
        val allStreams = call(iptvOrgStreamsLink).toData<List<Stream>>()
        val allLogos = call(iptvOrgLogosLink).toData<List<Logo>>()
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
        val playlistItems = getPlaylists().mapNotNull { playlist ->
            val items = try {
                call(playlist.second)
                    .toPlaylistSearch(titleToId(playlist.first), parser, query)
            } catch (_: Exception) {
                emptyList()
            }
            Shelf.Lists.Items(
                "${titleToId(playlist.first)}_search",
                playlist.first,
                items.take(12),
                more = items.takeIf { items.size > 12 }?.map { it.toShelf() }?.toFeed()
            ).takeUnless { items.isEmpty() }
        }
        val iptvOrgSearch = try {
            call(iptvOrgChannelsLink).toIptvOrgSearch(query)
        } catch (_: Exception) {
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
