package com.qymusic.app.utils.databinding

import android.widget.ImageButton
import android.widget.TextView
import androidx.databinding.BindingAdapter
import com.qymusic.app.utils.formatDuration

object DataBindingComponent {

  @BindingAdapter("time")
  @JvmStatic
  fun setTime(textView: TextView, ms: Long) {
    textView.text = ms.formatDuration()
  }

}