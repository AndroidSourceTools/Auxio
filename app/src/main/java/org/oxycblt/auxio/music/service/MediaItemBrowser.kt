/*
 * Copyright (c) 2024 Auxio Project
 * MediaItemBrowser.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.music.service

import android.content.Context
import androidx.media3.common.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import org.oxycblt.auxio.music.Album
import org.oxycblt.auxio.music.Artist
import org.oxycblt.auxio.music.Genre
import org.oxycblt.auxio.music.Music
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.Playlist
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.music.device.DeviceLibrary
import org.oxycblt.auxio.music.user.UserLibrary
import org.oxycblt.auxio.search.SearchEngine

class MediaItemBrowser
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val searchEngine: SearchEngine
) : MusicRepository.UpdateListener {
    private val browserJob = Job()
    private val searchScope = CoroutineScope(browserJob + Dispatchers.Default)
    private val searchResults = mutableMapOf<String, Deferred<SearchEngine.Items>>()
    private var invalidator: Invalidator? = null

    interface Invalidator {
        fun invalidate(ids: List<String>)
    }

    fun attach(invalidator: Invalidator) {
        this.invalidator = invalidator
        musicRepository.addUpdateListener(this)
    }

    fun release() {
        browserJob.cancel()
        invalidator = null
        musicRepository.removeUpdateListener(this)
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        val deviceLibrary = musicRepository.deviceLibrary
        var invalidateSearch = false
        val invalidate = mutableListOf<MediaSessionUID>()
        if (changes.deviceLibrary && deviceLibrary != null) {
            invalidate.addAll(MediaSessionUID.Category.DEVICE_MUSIC)
            deviceLibrary.albums.mapTo(invalidate) { MediaSessionUID.Single(it.uid) }
            deviceLibrary.artists.mapTo(invalidate) { MediaSessionUID.Single(it.uid) }
            deviceLibrary.genres.mapTo(invalidate) { MediaSessionUID.Single(it.uid) }
            invalidateSearch = true
        }
        val userLibrary = musicRepository.userLibrary
        if (changes.userLibrary && userLibrary != null) {
            invalidate.addAll(MediaSessionUID.Category.USER_MUSIC)
            userLibrary.playlists.mapTo(invalidate) { MediaSessionUID.Single(it.uid) }
            invalidateSearch = true
        }

        if (invalidate.isNotEmpty()) {
            invalidator?.invalidate(invalidate.map { it.toString() })
        }

        if (invalidateSearch) {
            for (entry in searchResults.entries) {
                entry.value.cancel()
            }
            searchResults.clear()
        }
    }

    val root: MediaItem
        get() = MediaSessionUID.Category.ROOT.toMediaItem(context)

    fun getItem(mediaId: String): MediaItem? {
        val music =
            when (val uid = MediaSessionUID.fromString(mediaId)) {
                is MediaSessionUID.Category -> return uid.toMediaItem(context)
                is MediaSessionUID.Single ->
                    musicRepository.find(uid.uid)?.let { musicRepository.find(it.uid) }
                is MediaSessionUID.Joined ->
                    musicRepository.find(uid.childUid)?.let { musicRepository.find(it.uid) }
                null -> null
            }
                ?: return null

        return when (music) {
            is Album -> music.toMediaItem(context)
            is Artist -> music.toMediaItem(context)
            is Genre -> music.toMediaItem(context)
            is Playlist -> music.toMediaItem(context)
            is Song -> music.toMediaItem(context, null)
        }
    }

    fun getChildren(parentId: String, page: Int, pageSize: Int): List<MediaItem>? {
        val deviceLibrary = musicRepository.deviceLibrary
        val userLibrary = musicRepository.userLibrary
        if (deviceLibrary == null || userLibrary == null) {
            return listOf()
        }

        val items = getMediaItemList(parentId, deviceLibrary, userLibrary) ?: return null
        return items.paginate(page, pageSize)
    }

    private fun getMediaItemList(
        id: String,
        deviceLibrary: DeviceLibrary,
        userLibrary: UserLibrary
    ): List<MediaItem>? {
        return when (val mediaSessionUID = MediaSessionUID.fromString(id)) {
            is MediaSessionUID.Category -> {
                when (mediaSessionUID) {
                    MediaSessionUID.Category.ROOT ->
                        MediaSessionUID.Category.IMPORTANT.map { it.toMediaItem(context) }
                    MediaSessionUID.Category.SONGS ->
                        deviceLibrary.songs.map { it.toMediaItem(context, null) }
                    MediaSessionUID.Category.ALBUMS ->
                        deviceLibrary.albums.map { it.toMediaItem(context) }
                    MediaSessionUID.Category.ARTISTS ->
                        deviceLibrary.artists.map { it.toMediaItem(context) }
                    MediaSessionUID.Category.GENRES ->
                        deviceLibrary.genres.map { it.toMediaItem(context) }
                    MediaSessionUID.Category.PLAYLISTS ->
                        userLibrary.playlists.map { it.toMediaItem(context) }
                }
            }
            is MediaSessionUID.Single -> {
                getChildMediaItems(mediaSessionUID.uid)
            }
            is MediaSessionUID.Joined -> {
                getChildMediaItems(mediaSessionUID.childUid)
            }
            null -> {
                return null
            }
        }
    }

    private fun getChildMediaItems(uid: Music.UID): List<MediaItem>? {
        return when (val item = musicRepository.find(uid)) {
            is Album -> {
                item.songs.map { it.toMediaItem(context, item) }
            }
            is Artist -> {
                (item.explicitAlbums + item.implicitAlbums).map { it.toMediaItem(context) } +
                    item.songs.map { it.toMediaItem(context, item) }
            }
            is Genre -> {
                item.songs.map { it.toMediaItem(context, item) }
            }
            is Playlist -> {
                item.songs.map { it.toMediaItem(context, item) }
            }
            is Song,
            null -> return null
        }
    }

    suspend fun prepareSearch(query: String): Int {
        val deviceLibrary = musicRepository.deviceLibrary
        val userLibrary = musicRepository.userLibrary
        if (deviceLibrary == null || userLibrary == null) {
            return 0
        }

        if (query.isEmpty()) {
            return 0
        }

        val deferred = searchTo(query, deviceLibrary, userLibrary)
        searchResults[query] = deferred
        return deferred.await().count()
    }

    suspend fun getSearchResult(
        query: String,
        page: Int,
        pageSize: Int,
    ): List<MediaItem>? {
        val deviceLibrary = musicRepository.deviceLibrary
        val userLibrary = musicRepository.userLibrary
        if (deviceLibrary == null || userLibrary == null) {
            return listOf()
        }

        if (query.isEmpty()) {
            return listOf()
        }

        val existing = searchResults[query]
        if (existing != null) {
            return existing.await().concat().paginate(page, pageSize)
        }

        return searchTo(query, deviceLibrary, userLibrary).await().concat().paginate(page, pageSize)
    }

    private fun SearchEngine.Items.concat(): MutableList<MediaItem> {
        val music = mutableListOf<MediaItem>()
        if (songs != null) {
            music.addAll(songs.map { it.toMediaItem(context, null) })
        }
        if (albums != null) {
            music.addAll(albums.map { it.toMediaItem(context) })
        }
        if (artists != null) {
            music.addAll(artists.map { it.toMediaItem(context) })
        }
        if (genres != null) {
            music.addAll(genres.map { it.toMediaItem(context) })
        }
        if (playlists != null) {
            music.addAll(playlists.map { it.toMediaItem(context) })
        }
        return music
    }

    private fun SearchEngine.Items.count(): Int {
        var count = 0
        if (songs != null) {
            count += songs.size
        }
        if (albums != null) {
            count += albums.size
        }
        if (artists != null) {
            count += artists.size
        }
        if (genres != null) {
            count += genres.size
        }
        if (playlists != null) {
            count += playlists.size
        }
        return count
    }

    private fun searchTo(query: String, deviceLibrary: DeviceLibrary, userLibrary: UserLibrary) =
        searchScope.async {
            val items =
                SearchEngine.Items(
                    deviceLibrary.songs,
                    deviceLibrary.albums,
                    deviceLibrary.artists,
                    deviceLibrary.genres,
                    userLibrary.playlists)
            searchEngine.search(items, query)
        }

    private fun List<MediaItem>.paginate(page: Int, pageSize: Int): List<MediaItem>? {
        if (page == Int.MAX_VALUE) {
            // I think if someone requests this page it more or less implies that I should
            // return all of the pages.
            return this
        }
        val start = page * pageSize
        val end = min((page + 1) * pageSize, size) // Tolerate partial page queries
        if (pageSize == 0 || start !in indices) {
            // These pages are probably invalid. Hopefully this won't backfire.
            return null
        }
        return subList(start, end).toMutableList()
    }
}
