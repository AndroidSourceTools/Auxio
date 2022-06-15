/*
 * Copyright (c) 2021 Auxio Project
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
 
package org.oxycblt.auxio.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentDetailBinding
import org.oxycblt.auxio.detail.recycler.DetailAdapter
import org.oxycblt.auxio.detail.recycler.GenreDetailAdapter
import org.oxycblt.auxio.detail.recycler.SortHeader
import org.oxycblt.auxio.music.Album
import org.oxycblt.auxio.music.Artist
import org.oxycblt.auxio.music.Genre
import org.oxycblt.auxio.music.Music
import org.oxycblt.auxio.music.MusicParent
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.playback.state.PlaybackMode
import org.oxycblt.auxio.ui.Header
import org.oxycblt.auxio.ui.Item
import org.oxycblt.auxio.ui.MenuFragment
import org.oxycblt.auxio.util.applySpans
import org.oxycblt.auxio.util.collectWith
import org.oxycblt.auxio.util.launch
import org.oxycblt.auxio.util.logD
import org.oxycblt.auxio.util.logW
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.unlikelyToBeNull

/**
 * A fragment that shows information for a particular [Genre].
 * @author OxygenCobalt
 */
class GenreDetailFragment :
    MenuFragment<FragmentDetailBinding>(), Toolbar.OnMenuItemClickListener, DetailAdapter.Listener {
    private val detailModel: DetailViewModel by activityViewModels()

    private val args: GenreDetailFragmentArgs by navArgs()
    private val detailAdapter = GenreDetailAdapter(this)

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentDetailBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentDetailBinding, savedInstanceState: Bundle?) {
        detailModel.setGenreId(args.genreId)

        binding.detailToolbar.apply {
            inflateMenu(R.menu.menu_genre_artist_detail)
            setNavigationOnClickListener { findNavController().navigateUp() }
            setOnMenuItemClickListener(this@GenreDetailFragment)
        }

        binding.detailRecycler.apply {
            adapter = detailAdapter
            applySpans { pos ->
                val item = detailAdapter.data.currentList[pos]
                item is Header || item is SortHeader || item is Genre
            }
        }

        // --- VIEWMODEL SETUP ---

        launch { detailModel.currentGenre.collect(::handleItemChange) }
        launch { detailModel.genreData.collect(detailAdapter.data::submitList) }
        launch { navModel.exploreNavigationItem.collect(::handleNavigation) }
        launch { playbackModel.song.collectWith(playbackModel.parent, ::updatePlayback) }
    }

    override fun onDestroyBinding(binding: FragmentDetailBinding) {
        super.onDestroyBinding(binding)
        binding.detailToolbar.setOnMenuItemClickListener(null)
        binding.detailRecycler.adapter = null
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_play_next -> {
                playbackModel.playNext(unlikelyToBeNull(detailModel.currentGenre.value))
                requireContext().showToast(R.string.lbl_queue_added)
                true
            }
            R.id.action_queue_add -> {
                playbackModel.addToQueue(unlikelyToBeNull(detailModel.currentGenre.value))
                requireContext().showToast(R.string.lbl_queue_added)
                true
            }
            else -> false
        }
    }

    override fun onItemClick(item: Item) {
        when (item) {
            is Song -> playbackModel.play(item, PlaybackMode.IN_GENRE)
            is Album ->
                findNavController()
                    .navigate(ArtistDetailFragmentDirections.actionShowAlbum(item.id))
        }
    }

    override fun onOpenMenu(item: Item, anchor: View) {
        when (item) {
            is Song -> musicMenu(anchor, R.menu.menu_song_actions, item)
            else -> logW("Unexpected datatype when opening menu: ${item::class.java}")
        }
    }

    override fun onPlayParent() {
        playbackModel.play(unlikelyToBeNull(detailModel.currentGenre.value), false)
    }

    override fun onShuffleParent() {
        playbackModel.play(unlikelyToBeNull(detailModel.currentGenre.value), true)
    }

    override fun onShowSortMenu(anchor: View) {
        menu(anchor, R.menu.menu_genre_sort) {
            val sort = detailModel.genreSort
            requireNotNull(menu.findItem(sort.itemId)).isChecked = true
            requireNotNull(menu.findItem(R.id.option_sort_asc)).isChecked = sort.isAscending
            setOnMenuItemClickListener { item ->
                item.isChecked = !item.isChecked
                detailModel.genreSort = requireNotNull(sort.assignId(item.itemId))
                true
            }
        }
    }

    private fun handleItemChange(genre: Genre?) {
        if (genre == null) {
            findNavController().navigateUp()
            return
        }

        requireBinding().detailToolbar.title = genre.resolveName(requireContext())
    }

    private fun handleNavigation(item: Music?) {
        when (item) {
            is Song -> {
                logD("Navigating to another song")
                findNavController()
                    .navigate(GenreDetailFragmentDirections.actionShowAlbum(item.album.id))
            }
            is Album -> {
                logD("Navigating to another album")
                findNavController().navigate(GenreDetailFragmentDirections.actionShowAlbum(item.id))
            }
            is Artist -> {
                logD("Navigating to another artist")
                findNavController()
                    .navigate(GenreDetailFragmentDirections.actionShowArtist(item.id))
            }
            is Genre -> {
                navModel.finishExploreNavigation()
            }
            null -> {}
        }
    }

    private fun updatePlayback(song: Song?, parent: MusicParent?) {
        if (parent is Genre && parent.id == unlikelyToBeNull(detailModel.currentGenre.value).id) {
            detailAdapter.highlightSong(song)
        } else {
            // Clear any highlighting if playback is not occuring from this item.
            detailAdapter.highlightSong(null)
        }
    }
}
