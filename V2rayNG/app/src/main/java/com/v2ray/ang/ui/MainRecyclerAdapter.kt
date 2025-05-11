package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AngApplication.Companion.application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.service.V2RayServiceManager
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainRecyclerAdapter(
    private val activity: MainActivity,
    private val mainViewModel: MainViewModel
) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {
    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
    }

    private val share_method: Array<out String> by lazy {
        activity.resources.getStringArray(R.array.share_method)
    }
    private val share_method_more: Array<out String> by lazy {
        activity.resources.getStringArray(R.array.share_method_more)
    }
    var isRunning = false
    private val doubleColumnDisplay = MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)

    /**
     * Gets the total number of items in the adapter (servers count + footer view)
     * @return The total item count
     */
    override fun getItemCount() = mainViewModel.serversCache.size + 1

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is MainViewHolder) {
            val guid = mainViewModel.serversCache[position].guid
            val profile = mainViewModel.serversCache[position].profile
            val isCustom = profile.configType == EConfigType.CUSTOM

            holder.itemView.setBackgroundColor(Color.TRANSPARENT)

            // Name address
            holder.itemMainBinding.tvName.text = profile.remarks
            holder.itemMainBinding.tvStatistics.text = getAddress(profile)
            holder.itemMainBinding.tvType.text = profile.configType.name

            // TestResult
            val aff = MmkvManager.decodeServerAffiliationInfo(guid)
            holder.itemMainBinding.tvTestResult.text = aff?.getTestDelayString().orEmpty()
            if ((aff?.testDelayMillis ?: 0L) < 0L) {
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(activity, R.color.colorPingRed))
            } else {
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(activity, R.color.colorPing))
            }

            // layoutIndicator
            if (guid == MmkvManager.getSelectServer()) {
                holder.itemMainBinding.layoutIndicator.setBackgroundResource(R.color.colorAccent)
            } else {
                holder.itemMainBinding.layoutIndicator.setBackgroundResource(0)
            }

            // subscription remarks
            val subRemarks = getSubscriptionRemarks(profile)
            holder.itemMainBinding.tvSubscription.text = subRemarks
            holder.itemMainBinding.layoutSubscription.visibility = if (subRemarks.isEmpty()) View.GONE else View.VISIBLE

            // layout
            if (doubleColumnDisplay) {
                holder.itemMainBinding.layoutShare.visibility = View.GONE
                holder.itemMainBinding.layoutEdit.visibility = View.GONE
                holder.itemMainBinding.layoutRemove.visibility = View.GONE
                holder.itemMainBinding.layoutMore.visibility = View.GONE

                // share method
                val shareOptions = if (isCustom) share_method_more.asList().takeLast(3) else share_method_more.asList()

                holder.itemMainBinding.layoutMore.setOnClickListener {
                    shareServer(guid, profile, position, shareOptions, if (isCustom) 2 else 0)
                }
            } else {
                holder.itemMainBinding.layoutShare.visibility = View.GONE
                holder.itemMainBinding.layoutEdit.visibility = View.GONE
                holder.itemMainBinding.layoutRemove.visibility = View.GONE
                holder.itemMainBinding.layoutMore.visibility = View.GONE

                // share method
                val shareOptions = if (isCustom) share_method.asList().takeLast(1) else share_method.asList()

                holder.itemMainBinding.layoutShare.setOnClickListener {
                    shareServer(guid, profile, position, shareOptions, if (isCustom) 2 else 0)
                }

                holder.itemMainBinding.layoutEdit.setOnClickListener {
                    editServer(guid, profile)
                }
                holder.itemMainBinding.layoutRemove.setOnClickListener {
                    removeServer(guid, position)
                }
            }

            holder.itemMainBinding.infoContainer.setOnClickListener {
                setSelectServer(guid)
            }
        }
        // if (holder is FooterViewHolder) {
        //     if (true) {
        //         holder.itemFooterBinding.layoutEdit.visibility = View.INVISIBLE
        //     } else {
        //         holder.itemFooterBinding.layoutEdit.setOnClickListener {
        //             Utils.openUri(activity, "${Utils.decode(AppConfig.PromotionUrl)}?t=${System.currentTimeMillis()}")
        //         }
        //     }
        // }
    }

    /**
     * Gets the server address information
     * Hides part of IP or domain information for privacy protection
     * @param profile The server configuration
     * @return Formatted address string
     */
    private fun getAddress(profile: ProfileItem): String {
        // Hide xxx:xxx:***/xxx.xxx.xxx.***
        return "${
            profile.server?.let {
                if (it.contains(":"))
                    it.split(":").take(2).joinToString(":", postfix = ":***")
                else
                    it.split('.').dropLast(1).joinToString(".", postfix = ".***")
            }
        } : ${profile.serverPort}"
    }

    /**
     * Gets the subscription remarks information
     * @param profile The server configuration
     * @return Subscription remarks string, or empty string if none
     */
    private fun getSubscriptionRemarks(profile: ProfileItem): String {
        val subRemarks =
            if (mainViewModel.subscriptionId.isEmpty())
                MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()
            else
                null
        return subRemarks?.toString() ?: ""
    }

    /**
     * Shares server configuration
     * Displays a dialog with sharing options and executes the selected action
     * @param guid The server unique identifier
     * @param profile The server configuration
     * @param position The position in the list
     * @param shareOptions The list of share options
     * @param skip The number of options to skip
     */
    private fun shareServer(guid: String, profile: ProfileItem, position: Int, shareOptions: List<String>, skip: Int) {
        AlertDialog.Builder(activity).setItems(shareOptions.toTypedArray()) { _, i ->
            try {
                when (i + skip) {
                    0 -> showQRCode(guid)
                    1 -> share2Clipboard(guid)
                    2 -> shareFullContent(guid)
                    3 -> editServer(guid, profile)
                    4 -> removeServer(guid, position)
                    else -> activity.toast("else")
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error when sharing server", e)
            }
        }.show()
    }

    /**
     * Displays QR code for the server configuration
     * @param guid The server unique identifier
     */
    private fun showQRCode(guid: String) {
        val ivBinding = ItemQrcodeBinding.inflate(LayoutInflater.from(activity))
        ivBinding.ivQcode.setImageBitmap(AngConfigManager.share2QRCode(guid))
        AlertDialog.Builder(activity).setView(ivBinding.root).show()
    }

    /**
     * Shares server configuration to clipboard
     * @param guid The server unique identifier
     */
    private fun share2Clipboard(guid: String) {
        if (AngConfigManager.share2Clipboard(activity, guid) == 0) {
            activity.toastSuccess(R.string.toast_success)
        } else {
            activity.toastError(R.string.toast_failure)
        }
    }

    /**
     * Shares full server configuration content to clipboard
     * @param guid The server unique identifier
     */
    private fun shareFullContent(guid: String) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(activity, guid)
            launch(Dispatchers.Main) {
                if (result == 0) {
                    activity.toastSuccess(R.string.toast_success)
                } else {
                    activity.toastError(R.string.toast_failure)
                }
            }
        }
    }

    /**
     * Edits server configuration
     * Opens appropriate editing interface based on configuration type
     * @param guid The server unique identifier
     * @param profile The server configuration
     */
    private fun editServer(guid: String, profile: ProfileItem) {
        val intent = Intent().putExtra("guid", guid)
            .putExtra("isRunning", isRunning)
            .putExtra("createConfigType", profile.configType.value)
        if (profile.configType == EConfigType.CUSTOM) {
            activity.startActivity(intent.setClass(activity, ServerCustomConfigActivity::class.java))
        } else {
            activity.startActivity(intent.setClass(activity, ServerActivity::class.java))
        }
    }

    /**
     * Removes server configuration
     * Handles confirmation dialog and related checks
     * @param guid The server unique identifier
     * @param position The position in the list
     */
    private fun removeServer(guid: String, position: Int) {
        if (guid != MmkvManager.getSelectServer()) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE) == true) {
                AlertDialog.Builder(activity).setMessage(R.string.del_config_comfirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        removeServerSub(guid, position)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        // do nothing
                    }
                    .show()
            } else {
                removeServerSub(guid, position)
            }
        } else {
            application.toast(R.string.toast_action_not_allowed)
        }
    }

    /**
     * Executes the actual server removal process
     * @param guid The server unique identifier
     * @param position The position in the list
     */
    private fun removeServerSub(guid: String, position: Int) {
        mainViewModel.removeServer(guid)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, mainViewModel.serversCache.size)
    }

    /**
     * Sets the selected server
     * Updates UI and restarts service if needed
     * @param guid The server unique identifier to select
     */
    private fun setSelectServer(guid: String) {
        val selected = MmkvManager.getSelectServer()
        if (guid != selected) {
            MmkvManager.setSelectServer(guid)
            if (!TextUtils.isEmpty(selected)) {
                notifyItemChanged(mainViewModel.getPosition(selected.orEmpty()))
            }
            notifyItemChanged(mainViewModel.getPosition(guid))
            if (isRunning) {
                V2RayServiceManager.stopVService(activity)
                activity.lifecycleScope.launch {
                    try {
                        delay(500)
                        V2RayServiceManager.startVService(activity)
                    } catch (e: Exception) {
                        Log.e(AppConfig.TAG, "Failed to restart V2Ray service", e)
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return when (viewType) {
            VIEW_TYPE_ITEM ->
                MainViewHolder(ItemRecyclerMainBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            else ->
                FooterViewHolder(ItemRecyclerFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == mainViewModel.serversCache.size) {
            VIEW_TYPE_FOOTER
        } else {
            VIEW_TYPE_ITEM
        }
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            itemView.setBackgroundColor(Color.LTGRAY)
        }

        fun onItemClear() {
            itemView.setBackgroundColor(0)
        }
    }

    class MainViewHolder(val itemMainBinding: ItemRecyclerMainBinding) :
        BaseViewHolder(itemMainBinding.root), ItemTouchHelperViewHolder

    class FooterViewHolder(val itemFooterBinding: ItemRecyclerFooterBinding) :
        BaseViewHolder(itemFooterBinding.root)

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        mainViewModel.swapServer(fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        // do nothing
    }

    override fun onItemDismiss(position: Int) {
    }
}
