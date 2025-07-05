package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.settings.Setting
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
data class Channel(
    val id: String,
    val name: String,
    val owners: List<String>,
    val country: String,
    @SerialName("is_nsfw")
    val isNsfw: Boolean,
    val logo: String,
)

@Serializable
data class Stream(
    val channel: String? = null,
    val url: String,
    val quality: String? = null,
)

class TestExtension : ExtensionClient, HomeFeedClient, TrackClient, SearchFeedClient {
    override suspend fun onExtensionSelected() {}

    override val settingItems get() = emptyList<Setting>()

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    private val channelsLink = "https://iptv-org.github.io/api/channels.json"
    private val streamsLink = "https://iptv-org.github.io/api/streams.json"
    private val countriesLink = "https://iptv-org.github.io/api/countries.json"

    private val client by lazy { OkHttpClient.Builder().build() }
    private suspend fun call(url: String) = client.newCall(
        Request.Builder().url(url).build()
    ).await().body.string()

    private val json by lazy { Json { ignoreUnknownKeys = true } }
    private inline fun <reified T> String.toData() =
        runCatching { json.decodeFromString<T>(this) }.getOrElse {
            throw IllegalStateException("Failed to parse JSON: $this", it)
        }

    private suspend fun String.toShelf(countryCode: String): List<Shelf> {
        val allStreams = call(streamsLink).toData<List<Stream>>()
        return this.toData<List<Channel>>().filter {
                !it.isNsfw && it.country == countryCode
            }.map {
                Track(
                    id = it.id,
                    title = it.name,
                    subtitle = it.owners.joinToString(", "),
                    cover = it.logo.toImageHolder(),
                    streamables = allStreams.filter { ch -> ch.channel == it.id }
                        .mapIndexed { idx, ch -> Streamable.server(ch.url, idx, ch.quality) }
                ).toMediaItem().toShelf()
            }
    }

    override fun getHomeFeed(tab: Tab?) = PagedData.Single {
        call(channelsLink).toShelf(tab!!.id)
    }.toFeed()

    override suspend fun getHomeTabs(): List<Tab> {
        return call(countriesLink).toData<List<Country>>().map {
            Tab(title = it.name, id = it.code)
        }
    }

    override fun getShelves(track: Track): PagedData<Shelf> {
        return PagedData.empty()
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        return Streamable.Media.Server(
            listOf(streamable.id.toSource(type = Streamable.SourceType.HLS, isVideo = true)),
            false
        )
    }

    override suspend fun loadTrack(track: Track) = track

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {}
    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        return emptyList()
    }

    private suspend fun String.toSearchShelf(query: String): List<Shelf> {
        val allStreams = call(streamsLink).toData<List<Stream>>()
        return this.toData<List<Channel>>().filter {
            !it.isNsfw && it.name.contains(query, true)
        }.take(100).map {
            Track(
                id = it.id,
                title = it.name,
                subtitle = it.owners.joinToString(", "),
                cover = it.logo.toImageHolder(),
                streamables = allStreams.filter { ch -> ch.channel == it.id }
                    .mapIndexed { idx, ch -> Streamable.server(ch.url, idx, ch.quality) }
            ).toMediaItem().toShelf()
        }
    }

    override fun searchFeed(query: String, tab: Tab?) =
        PagedData.Single {
            call(channelsLink).toSearchShelf(query)
        }.toFeed()

    override suspend fun searchTabs(query: String) = emptyList<Tab>()
}
