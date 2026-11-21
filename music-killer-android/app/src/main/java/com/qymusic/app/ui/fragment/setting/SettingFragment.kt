package com.qymusic.app.ui.fragment.setting

import android.content.Intent
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.navigation.fragment.findNavController
import com.drake.tooltip.toast
import com.qymusic.app.BuildConfig
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.databinding.FragmentSettingBinding
import com.qymusic.app.utils.AppUpdater
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 设置页：只保留「关于」分区（版本、更新、协议、许可、官网与联系）。
 */
class SettingFragment : BaseMusicAppbarFragment<FragmentSettingBinding>(R.layout.fragment_setting) {

    companion object {
        private const val WEBSITE = "https://www.qyder.com"
        private const val EMAIL = "qyder8@gmail.com"
    }

    override fun initView() {
        baseContentBinding.root.setBackgroundResource(R.drawable.bg_settings_glass)
        baseContentBinding.appbar.setBackgroundColor(Color.TRANSPARENT)
        baseContentBinding.toolbar.setBackgroundColor(Color.TRANSPARENT)

        binding.itemWebsite.setOnClickListener { openWebsite() }
        binding.itemContact.setOnClickListener { sendEmail() }
        binding.itemCheckUpdate.setOnClickListener {
            AppUpdater.check(requireActivity() as AppCompatActivity, manual = true)
        }
        binding.itemLicense.setOnClickListener {
            findNavController().navigate(R.id.action_setting_license)
        }
        binding.itemTerms.setOnClickListener {
            showLongTextDialog("用户协议", getString(R.string.terms_content))
        }
        binding.itemPrivacy.setOnClickListener {
            showLongTextDialog("隐私政策", getString(R.string.privacy_content))
        }
    }

    override fun initData() {
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
    }

    private fun refreshSummary() {
        binding.tvVersion.text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.tvUpdateState.text =
            "当前 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · 点击检查"
        binding.tvContactEmail.text = EMAIL
    }

    private fun showLongTextDialog(title: String, content: String) {
        if (!isAdded) {
            return
        }
        val text = androidx.core.text.HtmlCompat.fromHtml(
            content,
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun openWebsite() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, WEBSITE.toUri()))
        } catch (e: Exception) {
            toast("没有找到可用的浏览器")
        }
    }

    private fun sendEmail() {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, "mailto:$EMAIL".toUri()))
        } catch (e: Exception) {
            toast("没有找到可用的邮件应用")
        }
    }
}
