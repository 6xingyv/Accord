/*
 *     Copyright (C) 2024 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.logic.ui

import android.content.Context
import android.text.TextUtils.TruncateAt
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.fluidrecyclerview.widget.LinearLayoutManager
import androidx.fluidrecyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import me.zhanghai.android.fastscroll.DefaultAnimationHelper
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.fastscroll.FixOnItemTouchListenerRecyclerView
import me.zhanghai.android.fastscroll.PopupTextProvider
import org.akanework.gramophone.R

// Please don't try to understand it :) this abstracts away all the ugly details you rather not know
class MyRecyclerView(context: Context, attributeSet: AttributeSet?, defStyleAttr: Int) :
    FixOnItemTouchListenerRecyclerView(context, attributeSet, defStyleAttr),
    AppBarLayout.OnOffsetChangedListener {
    constructor(context: Context, attributeSet: AttributeSet?)
            : this(context, attributeSet, 0)

    constructor(context: Context) : this(context, null)

    private var appBarLayout: AppBarLayout? = null
    private var ah: MyAnimationHelper? = null
    private var scrollInProgress = false
    private var scrollIsNatural = false

    fun setAppBar(appBarLayout: AppBarLayout) {
        this.appBarLayout = appBarLayout
        appBarLayout.addOnOffsetChangedListener(this)
    }

    private fun setAppBarExpanded(expanded: Boolean) {
        val behavior = (appBarLayout?.layoutParams as CoordinatorLayout.LayoutParams?)?.behavior
        val isExpanded = behavior is AppBarLayout.Behavior && behavior.topAndBottomOffset == 0
        if (isExpanded != expanded) {
            appBarLayout?.setExpanded(expanded, true)
        }
    }

    fun startSmoothScrollCompat(scroller: SmoothScroller) {
        scrollInProgress = true
        val isZero = (layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition() == 0
        if (isZero && scroller.targetPosition > 0)
            setAppBarExpanded(false)
        layoutManager?.startSmoothScroll(scroller)
    }

    @Suppress("UNCHECKED_CAST")
    fun scrollToPositionWithOffsetCompat(position: Int, offset: Int) {
        val apb = (appBarLayout?.layoutParams as CoordinatorLayout.LayoutParams?)?.behavior
        val isExpanded = apb is AppBarLayout.Behavior && apb.topAndBottomOffset == 0
        if (appBarLayout != null && (position > 0 || offset > 0) && isExpanded) {
            // this is setAppBarExpanded(false) but it works without layout pass
            val behavior: CoordinatorLayout.Behavior<AppBarLayout>? =
                (appBarLayout!!.layoutParams as CoordinatorLayout.LayoutParams).behavior
                        as CoordinatorLayout.Behavior<AppBarLayout>?
            val a = IntArray(2)
            behavior!!.onNestedPreScroll(
                appBarLayout!!.parent!! as CoordinatorLayout, appBarLayout!!,
                appBarLayout!!.parent!! as CoordinatorLayout, 0, Int.MAX_VALUE,
                a, 0
            )
            // in theory, we need to scroll recyclerview back by dy=-a[1] but it does not make
            // sense because the CollapsingToolbar is pushing away our scroll bar to bottom
            // and when the scroll bar goes back up after collapse, the scale is different anyway
            // and we have an ugly jump in the list. I appreciate any idea for improvements.
        }
        (layoutManager as LinearLayoutManager?)?.scrollToPositionWithOffset(position, offset)
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        if (state == SCROLL_STATE_DRAGGING) {
            scrollInProgress = true
            scrollIsNatural = true
        } else if (!scrollIsNatural && state == SCROLL_STATE_SETTLING) {
            scrollInProgress = true
        } else if (state == SCROLL_STATE_IDLE) {
            if (scrollInProgress && !scrollIsNatural) {
                val pos =
                    (layoutManager as? LinearLayoutManager)?.findFirstCompletelyVisibleItemPosition()
                setAppBarExpanded(pos == 0)
            }
            scrollInProgress = false
            scrollIsNatural = false
        }
    }

    fun fastScroll(popupTextProvider: PopupTextProvider?, ihh: ItemHeightHelper?): FastScroller {
        val popupStyles =
            Consumer { popupView: TextView ->
                val resources = popupView.resources
                popupView.setMinimumWidth(resources.getDimensionPixelSize(R.dimen.afs_md2_popup_min_width))
                popupView.setMinimumHeight(resources.getDimensionPixelSize(R.dimen.afs_md2_popup_min_height))
                val layoutParams =
                    popupView.layoutParams as FrameLayout.LayoutParams
                layoutParams.gravity = 49
                layoutParams.setMarginEnd(resources.getDimensionPixelOffset(R.dimen.afs_md2_popup_margin_end))
                popupView.setLayoutParams(layoutParams)
                val context = popupView.context
                popupView.background =
                    AppCompatResources.getDrawable(context, R.drawable.afs_popup_background)
                popupView.elevation =
                    resources.getDimensionPixelOffset(R.dimen.afs_md2_popup_elevation).toFloat()
                popupView.ellipsize = TruncateAt.MIDDLE
                popupView.setGravity(17)
                popupView.includeFontPadding = false
                popupView.setSingleLine(true)
                popupView.setTextColor(
                    ContextCompat.getColor(
                        context,
                        R.color.contrast_colorBackground
                    )
                )
                popupView.setTextSize(
                    0,
                    resources.getDimensionPixelSize(R.dimen.afs_md2_popup_text_size).toFloat()
                )
            }
        return FastScrollerBuilder(this)
            .setViewHelper(RecyclerViewHelper(this, popupTextProvider, ihh))
            .also { builder -> builder.setAnimationHelper(MyAnimationHelper().also { ah = it }) }
            .useMd2Style()
            .setTrackDrawable(
                AppCompatResources.getDrawable(
                    context,
                    R.drawable.ic_transparent
                )!!
            )
            .setThumbDrawable(
                AppCompatResources.getDrawable(
                    context,
                    R.drawable.fastscroller_thumb
                )!!
            )
            .setPopupStyle(popupStyles)
            .build()
    }

    override fun onOffsetChanged(unused: AppBarLayout?, offset: Int) {
        if (offset == 0) ah?.hideScrollbar()
    }

    abstract class Adapter<VH : ViewHolder> : RecyclerView.Adapter<VH>() {
        final override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            super.onAttachedToRecyclerView(recyclerView)
            onAttachedToRecyclerView(recyclerView as MyRecyclerView)
        }

        final override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            super.onDetachedFromRecyclerView(recyclerView)
            onDetachedFromRecyclerView(recyclerView as MyRecyclerView)
        }

        open fun onAttachedToRecyclerView(recyclerView: MyRecyclerView) {}
        open fun onDetachedFromRecyclerView(recyclerView: MyRecyclerView) {}
    }

    private inner class MyAnimationHelper : DefaultAnimationHelper(this) {
        var trackViewCache: View? = null
        var thumbViewCache: View? = null

        override fun showScrollbar(trackView: View, thumbView: View) {
            super.showScrollbar(trackView, thumbView)
            this.trackViewCache = trackView
            this.thumbViewCache = thumbView
        }

        override fun hideScrollbar(trackView: View, thumbView: View) {
            super.hideScrollbar(trackView, thumbView)
            this.trackViewCache = null
            this.thumbViewCache = null
        }

        fun hideScrollbar() {
            if (trackViewCache != null)
                hideScrollbar(trackViewCache!!, thumbViewCache!!)
        }
    }
}

abstract class DefaultItemHeightHelper : ItemHeightHelper {
    companion object {
        fun concatItemHeightHelper(
            one: ItemHeightHelper,
            oneCount: () -> Int,
            two: ItemHeightHelper
        ): ItemHeightHelper {
            return object : DefaultItemHeightHelper() {
                override fun getItemHeightFromZeroTo(to: Int): Int {
                    val oc = oneCount()
                    val oh = one.getItemHeightFromZeroTo(to.coerceAtMost(oc))
                    val th = if (to >= oc) two.getItemHeightFromZeroTo(to - oc) else 0
                    return (oh + th)
                }
            }
        }
    }
}