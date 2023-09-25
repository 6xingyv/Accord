package org.akanework.gramophone.ui.adapters

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.PopupTextProvider
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.utils.MediaStoreUtils
import org.akanework.gramophone.logic.utils.SupportComparator
import org.akanework.gramophone.logic.utils.isSupertypeOrEquals
import java.util.Collections

abstract class BaseAdapter<T>(
	protected val context: Context,
	initialList: MutableList<T>,
	private val sorter: Sorter<T>,
	initialSortType: Sorter.Type
) : RecyclerView.Adapter<BaseAdapter<T>.ViewHolder>() {

	private val rawList = ArrayList<T>(initialList.size)
	protected val list = ArrayList<T>(initialList.size)
	private var comparator: Sorter.HintedComparator<T>? = null
	var sortType: Sorter.Type
		get() = comparator?.type!!
		private set(value) {
			if (comparator?.type != value) {
				comparator = sorter.getComparator(value)
			}
		}

	init {
		sortType = initialSortType
		updateList(initialList, true)
	}

	protected open val defaultCover: Int = R.drawable.ic_default_cover
	protected abstract val layout: Int

	inner class ViewHolder(
		view: View,
	) : RecyclerView.ViewHolder(view) {
		val songCover: ImageView = view.findViewById(R.id.cover)
		val title: TextView = view.findViewById(R.id.title)
		val subTitle: TextView = view.findViewById(R.id.artist)
		val moreButton: MaterialButton = view.findViewById(R.id.more)
	}

	fun getFrozenList(): List<T> {
		return Collections.unmodifiableList(list)
	}

	override fun getItemCount(): Int = list.size

	override fun onCreateViewHolder(
		parent: ViewGroup,
		viewType: Int,
	): ViewHolder =
		ViewHolder(
			LayoutInflater
				.from(parent.context)
				.inflate(layout, parent, false),
		)

	fun sort(selector: Sorter.Type) {
		sortType = selector
		CoroutineScope(Dispatchers.Default).launch {
			val apply = sort()
			withContext(Dispatchers.Main) {
				apply()
			}
		}
	}

	private fun sort(srcList: MutableList<T>? = null): () -> Unit {
		// Sorting in the background using coroutines
		val newList = ArrayList(srcList ?: rawList)
		newList.sortWith { o1, o2 ->
			if (isPinned(o1) && !isPinned(o2)) -1
			else if (!isPinned(o1) && isPinned(o2)) 1
			else comparator?.compare(o1, o2) ?: 0
		}
		val apply = updateListSorted(newList)
		return {
			if (srcList != null) {
				rawList.clear()
				rawList.addAll(srcList)
			}
			apply()
		}
	}

	private fun updateListSorted(newList: MutableList<T>): () -> Unit {
		val diffResult = DiffUtil.calculateDiff(SongDiffCallback(list, newList))
		return {
			list.clear()
			list.addAll(newList)
			diffResult.dispatchUpdatesTo(this)
		}
	}

	fun updateList(newList: MutableList<T>, now: Boolean = false) {
		if (now) sort(newList)()
		else {
			CoroutineScope(Dispatchers.Default).launch {
				val apply = sort(newList)
				withContext(Dispatchers.Main) {
					apply()
				}
			}
		}
	}

	final override fun onBindViewHolder(
		holder: ViewHolder,
		position: Int,
	) {
		val item = list[position]
		holder.title.text = titleOf(item)
		holder.subTitle.text = subTitleOf(item)
		Glide
			.with(holder.songCover.context)
			.load(coverOf(item))
			.placeholder(defaultCover)
			.into(holder.songCover)
		holder.itemView.setOnClickListener { onClick(item) }
		holder.moreButton.setOnClickListener {
			val popupMenu = PopupMenu(it.context, it)
			onMenu(item, popupMenu)
			popupMenu.show()
		}
	}

	abstract fun toId(item: T): String
	abstract fun titleOf(item: T): String
	abstract fun subTitleOf(item: T): String
	abstract fun coverOf(item: T): Uri?
	protected abstract fun onClick(item: T)
	protected abstract fun onMenu(item: T, popupMenu: PopupMenu)
	protected open fun isPinned(item: T): Boolean {
		return false
	}

	private inner class SongDiffCallback(
		private val oldList: MutableList<T>,
		private val newList: MutableList<T>,
	) : DiffUtil.Callback() {
		override fun getOldListSize() = oldList.size

		override fun getNewListSize() = newList.size

		override fun areItemsTheSame(
			oldItemPosition: Int,
			newItemPosition: Int,
		) = toId(oldList[oldItemPosition]) == toId(newList[newItemPosition])

		override fun areContentsTheSame(
			oldItemPosition: Int,
			newItemPosition: Int,
		) = oldList[oldItemPosition] == newList[newItemPosition]
	}

	protected fun toRawPos(item: T): Int {
		return rawList.indexOf(item)
	}

	abstract class ItemAdapter<T : MediaStoreUtils.Item>(context: Context,
	                                                     rawList: MutableList<T>,
	                                                     sorter: Sorter<T>,
	                                                     initialSortType: Sorter.Type
	                                                        = Sorter.Type.ByTitleAscending
	) : BaseAdapter<T>(context, rawList, sorter, initialSortType) {
		override fun toId(item: T): String {
			return item.id.toString()
		}

		override fun subTitleOf(item: T): String {
			return context.resources.getQuantityString(
				R.plurals.songs, item.songList.size, item.songList.size)
		}

		override fun coverOf(item: T): Uri? {
			return item.songList
				.firstOrNull()
				?.mediaMetadata
				?.artworkUri
		}
	}

	open class BasePopupTextProvider<T>(private val adapter: BaseAdapter<T>) : PopupTextProvider {
		final override fun getPopupText(position: Int): CharSequence {
			return (if (position != 0)
				getTitleFor(adapter.list[position])?.first()?.toString()
			else null) ?: "-"
		}

		open fun getTitleFor(item: T): String? {
			return adapter.titleOf(item)
		}
	}

	class Sorter<T> private constructor(private val sortingHelper: Helper<T>) {
		companion object {
			fun <T> internalCreateSorter(sortingHelper: Helper<T>): Sorter<T> {
				return Sorter(sortingHelper)
			}

			fun <T : MediaStoreUtils.Item> internalFromStoreItem(
					@Suppress("UNUSED_PARAMETER") dummy: T?): Helper<T> {
				return StoreItemHelper()
			}

			fun <T> internalNoneSortHelper(): Helper<T> {
				return NoneHelper()
			}

			fun internalFromStoreAlbum(): Helper<MediaStoreUtils.Album> {
				return StoreAlbumHelper()
			}

			fun internalFromMediaItem(): Helper<MediaItem> {
				return MediaItemHelper()
			}

			@Suppress("UNCHECKED_CAST")
			inline fun <reified T> from(dummy: T?): Sorter<T> {
				return internalCreateSorter(
					if (T::class.isSupertypeOrEquals(MediaStoreUtils.Album::class)) {
						internalFromStoreAlbum() as Helper<T>
					} else if (T::class.isSupertypeOrEquals(MediaStoreUtils.Item::class)) {
						internalFromStoreItem(dummy as MediaStoreUtils.Item?) as Helper<T>
					} else if (T::class.isSupertypeOrEquals(MediaItem::class)) {
						internalFromMediaItem() as Helper<T>
					} else throw IllegalArgumentException("Unsupported: ${T::class.qualifiedName}")
				)
			}
			inline fun <reified T> from(): Sorter<T> {
				return from(dummy = null)
			}
			inline fun <reified T> noneSorter(): Sorter<T> {
				return internalCreateSorter(internalNoneSortHelper())
			}
		}

		abstract class Helper<T>(typesSupported: Set<Type>) {
			val typesSupported = typesSupported.toMutableSet().apply { add(Type.None) }.toSet()
			abstract fun getTitle(item: T): String
			open fun getArtist(item: T): String = throw UnsupportedOperationException()
			open fun getAlbumTitle(item: T): String = throw UnsupportedOperationException()
			open fun getAlbumArtist(item: T): String = throw UnsupportedOperationException()
			open fun getSize(item: T): Int = throw UnsupportedOperationException()
		}

		private class NoneHelper<T> : Helper<T>(setOf(Type.None)) {
			override fun getTitle(item: T) = throw UnsupportedOperationException()
		}

		private open class StoreItemHelper<T : MediaStoreUtils.Item>(
			typesSupported: Set<Type> = setOf(
				Type.ByTitleDescending, Type.ByTitleAscending,
				Type.BySizeDescending, Type.BySizeAscending
			)
		) : Helper<T>(typesSupported) {
			override fun getTitle(item: T): String {
				return item.title.toString()
			}

			override fun getSize(item: T): Int {
				return item.songList.size
			}
		}

		private class StoreAlbumHelper : StoreItemHelper<MediaStoreUtils.Album>(
			setOf(
				Type.ByTitleDescending, Type.ByTitleAscending,
				Type.ByArtistDescending, Type.ByArtistAscending,
				Type.BySizeDescending, Type.BySizeAscending
			)
		) {
			override fun getArtist(item: MediaStoreUtils.Album): String {
				return item.artist ?: ""
			}
		}

		private class MediaItemHelper : Helper<MediaItem>(setOf(
			Type.ByTitleDescending, Type.ByTitleAscending,
			Type.ByArtistDescending, Type.ByArtistAscending,
			Type.ByAlbumTitleDescending, Type.ByAlbumTitleAscending,
			Type.ByAlbumArtistDescending, Type.ByAlbumArtistAscending)) {
			override fun getTitle(item: MediaItem): String {
				return item.mediaMetadata.title.toString()
			}

			override fun getArtist(item: MediaItem): String {
				return item.mediaMetadata.artist?.toString() ?: ""
			}

			override fun getAlbumTitle(item: MediaItem): String {
				return item.mediaMetadata.albumTitle?.toString() ?: ""
			}

			override fun getAlbumArtist(item: MediaItem): String {
				return item.mediaMetadata.albumArtist?.toString() ?: ""
			}
		}

		enum class Type {
			ByTitleDescending, ByTitleAscending,
			ByArtistDescending, ByArtistAscending,
			ByAlbumTitleDescending, ByAlbumTitleAscending,
			ByAlbumArtistDescending, ByAlbumArtistAscending,
			BySizeDescending, BySizeAscending,
			None
		}

		fun getSupportedTypes(): Set<Type> {
			return sortingHelper.typesSupported
		}

		fun getComparator(type: Type): HintedComparator<T> {
			if (!getSupportedTypes().contains(type))
				throw IllegalArgumentException("Unsupported type ${type.name}")
			return WrappingHintedComparator(type, when (type) {
				Type.ByTitleDescending -> {
					SupportComparator.createAlphanumericComparator(true) {
						sortingHelper.getTitle(it) }
				}
				Type.ByTitleAscending -> {
					SupportComparator.createAlphanumericComparator(false) {
						sortingHelper.getTitle(it) }
				}
				Type.ByArtistDescending -> {
					SupportComparator.createAlphanumericComparator(true) {
						sortingHelper.getArtist(it) }
				}
				Type.ByArtistAscending -> {
					SupportComparator.createAlphanumericComparator(false) {
						sortingHelper.getArtist(it) }
				}
				Type.ByAlbumTitleDescending -> {
					SupportComparator.createAlphanumericComparator(true) {
						sortingHelper.getAlbumTitle(it) }
				}
				Type.ByAlbumTitleAscending -> {
					SupportComparator.createAlphanumericComparator(false) {
						sortingHelper.getAlbumTitle(it) }
				}
				Type.ByAlbumArtistDescending -> {
					SupportComparator.createAlphanumericComparator(true) {
						sortingHelper.getAlbumArtist(it) }
				}
				Type.ByAlbumArtistAscending -> {
					SupportComparator.createAlphanumericComparator(false) {
						sortingHelper.getAlbumArtist(it) }
				}
				Type.BySizeDescending -> {
					SupportComparator.createInversionComparator(
						compareBy { sortingHelper.getSize(it) }, true)
				}
				Type.BySizeAscending -> {
					SupportComparator.createInversionComparator(
						compareBy { sortingHelper.getSize(it) }, false)
				}
				Type.None -> SupportComparator.createDummyComparator()
			})
		}

		abstract class HintedComparator<T>(val type: Type) : Comparator<T>
		private class WrappingHintedComparator<T>(type: Type, private val comparator: Comparator<T>)
					: HintedComparator<T>(type) {
			override fun compare(o1: T, o2: T): Int {
				return comparator.compare(o1, o2)
			}
		}
	}

}