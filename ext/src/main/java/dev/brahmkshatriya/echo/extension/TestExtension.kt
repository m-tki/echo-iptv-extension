package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.settings.SettingList
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

class TestExtension : ExtensionClient, HomeFeedClient, TrackClient, SearchFeedClient {
    override suspend fun onExtensionSelected() {}

    override suspend fun onInitialize() {
        if (setting.getBoolean("countries_initialized") == null)  {
            setting.putString("countries_serialized", call(countriesLink))
            setting.putBoolean("countries_initialized", true)
        }
    }

    override suspend fun getSettingItems() = listOf(
        SettingList(
            "Default Country",
            "default_country_code",
            "Select a default country to be displayed as the first tab on the home page",
            setting.getString("countries_serialized")!!.toData<List<Country>>().map { it.name },
            setting.getString("countries_serialized")!!.toData<List<Country>>().map { it.code }
        )
    )

    private val defaultCountryCode get() = setting.getString("default_country_code")

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    private val channelsLink = "https://iptv-org.github.io/api/channels.json"
    private val streamsLink = "https://iptv-org.github.io/api/streams.json"
    private val countriesLink = "https://iptv-org.github.io/api/countries.json"
    private val categoriesLink = "https://iptv-org.github.io/api/categories.json"
    private val logosLink = "https://iptv-org.github.io/api/logos.json"

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

    private fun getTracks(allChannels: List<Channel>, allStreams: List<Stream>, allLogos: List<Logo>, categoryId: String): List<Shelf> =
        allChannels.filter {
            it.categories.any { id -> id == categoryId } ||
                    (it.categories.isEmpty() && categoryId == "unknown")
        }.map {
            val isAvailable = allStreams.any { ch -> ch.channel == it.id }
            val subtitle = it.owners.joinToString(", ")
            Track(
                it.id,
                it.name,
                cover = allLogos.firstOrNull { logo -> it.id == logo.channel }?.url?.toImageHolder(),
                subtitle = if (isAvailable) subtitle else {
                    if (subtitle.isEmpty()) "Not Supported" else "Not Supported - $subtitle"
                },
                streamables = allStreams.filter { ch -> ch.channel == it.id }
                    .mapIndexed { idx, ch -> Streamable.server(ch.url, idx, ch.quality) },
                isPlayable = if (isAvailable) Track.Playable.Yes else
                    Track.Playable.No("No Available Streams")
            ).toShelf()
        }

    private suspend fun String.toShelf(countryCode: String): List<Shelf> {
        val allCategories = call(categoriesLink).toData<List<Category>>()
        val allChannels = this.toData<List<Channel>>().filter {
            !it.isNsfw && it.country == countryCode
        }
        val allCategoriesFiltered = allCategories.filter { allChannels.any { ch ->
            ch.categories.any { id -> id == it.id } } }.toMutableList()
        if (allChannels.any { it.categories.isEmpty() }) {
            allCategoriesFiltered.add(Category(name = "Unknown", id = "unknown"))
        }
        val allStreams = call(streamsLink).toData<List<Stream>>()
        val allLogos = call(logosLink).toData<List<Logo>>()
        return listOf(
            Shelf.Lists.Categories(
                "categories",
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

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val countries = call(countriesLink).toData<List<Country>>()
        val (default, others) = countries.partition { it.code == defaultCountryCode }
        return listOf(
            Shelf.Lists.Categories(
                "countries",
                "Countries",
                (default + others).map {
                    Shelf.Category(
                        it.code,
                        it.name,
                        PagedData.Single {
                            call(channelsLink).toShelf(it.code)
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
        return Streamable.Media.Server(
            listOf(streamable.id.toSource(
                type = Streamable.SourceType.HLS,
                isVideo = true,
                isLive = true
            )),
            false
        )
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track

    private suspend fun String.toSearchShelf(query: String): List<Shelf> {
        val allStreams = call(streamsLink).toData<List<Stream>>()
        val allLogos = call(logosLink).toData<List<Logo>>()
        return this.toData<List<Channel>>().filter {
            !it.isNsfw && it.name.contains(query, true)
        }.take(100).map {
            Track(
                it.id,
                it.name,
                cover = allLogos.firstOrNull { logo -> it.id == logo.channel }?.url?.toImageHolder(),
                subtitle = it.owners.joinToString(", "),
                streamables = allStreams.filter { ch -> ch.channel == it.id }
                    .mapIndexed { idx, ch -> Streamable.server(ch.url, idx, ch.quality) }
            ).toShelf()
        }
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> =
        call(channelsLink).toSearchShelf(query).toFeed()
}
