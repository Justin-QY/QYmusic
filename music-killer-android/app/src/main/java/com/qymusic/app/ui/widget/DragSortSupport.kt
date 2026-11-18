package com.qymusic.app.ui.widget

import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.qymusic.app.R

/**
 * 长按拖动排序的通用实现（ItemTouchHelper）：
 * 拖动中整行抬起 + 高亮，松手回调 onDrop 保存顺序。
 *
 * 「插件管理」与「底部导航管理」共用，列表数量很少，无需额外依赖。
 */
object DragSortSupport {

  fun attach(
    recyclerView: RecyclerView,
    move: (Int, Int) -> Boolean,
    onDrop: () -> Unit,
  ) {
    val context = recyclerView.context
    val callback = object : ItemTouchHelper.SimpleCallback(
      ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
      override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
      ): Boolean {
        return move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
      }

      override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
      }

      override fun isLongPressDragEnabled(): Boolean = true

      override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
          viewHolder?.itemView?.let { liftRow(it) }
        }
      }

      override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        dropRow(viewHolder.itemView)
        onDrop()
      }

      private fun liftRow(view: View) {
        val elevation = 8 * view.resources.displayMetrics.density
        view.background = ContextCompat.getDrawable(context, R.drawable.bg_module_row_drag)
        view.elevation = elevation
        view.translationZ = elevation
        view.alpha = 0.96f
        view.scaleX = 1.01f
        view.scaleY = 1.01f
      }

      private fun dropRow(view: View) {
        view.background = ContextCompat.getDrawable(context, R.drawable.bg_module_row)
        view.elevation = 0f
        view.translationZ = 0f
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
      }
    }
    ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
  }
}
