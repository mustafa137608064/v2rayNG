package com.v2ray.ang.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MigrateManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.service.V2RayServiceManager
import com.v2ray.ang.util.AppManagerUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.v2ray.ang.repository.Api
import android.text.TextUtils
import com.tencent.mmkv.MMKV
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import com.v2ray.ang.dto.SubscriptionItem

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            lifecycleScope.launch {
                startV2Ray()
            }
        }
    }
    private val requestSubSettingActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        initGroupTab()
    }
    private val tabGroupListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            val selectId = tab?.tag.toString()
            if (selectId != mainViewModel.subscriptionId) {
                mainViewModel.subscriptionIdChanged(selectId)
            }
        }

        override fun onTabUnselected(tab: TabLayout.Tab?) {}
        override fun onTabReselected(tab: TabLayout.Tab?) {}
    }
    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by viewModels()

    private var isUpdatingServers = false

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            when (pendingAction) {
                Action.IMPORT_QR_CODE_CONFIG -> scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
                Action.READ_CONTENT_FROM_URI -> chooseFileForCustomConfig.launch(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }, getString(R.string.title_file_chooser)))
                Action.POST_NOTIFICATIONS -> {}
                else -> {}
            }
        } else {
            toast(R.string.toast_permission_denied)
        }
        pendingAction = Action.NONE
    }

    private var pendingAction: Action = Action.NONE

    enum class Action {
        NONE,
        IMPORT_QR_CODE_CONFIG,
        READ_CONTENT_FROM_URI,
        POST_NOTIFICATIONS
    }

    private val chooseFileForCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = it.data?.data
        if (it.resultCode == RESULT_OK && uri != null) {
            readContentFromUri(uri)
        }
    }

    private val scanQRCodeForConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            val server

 = it.data?.getStringExtra("SCAN_RESULT")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                    delay(500L)
                    withContext(Dispatchers.Main) {
                        when {
                            count > 0 -> {
                                toast(getString(R.string.title_import_config_count, count))
                                mainViewModel.reloadServerList()
                            }
                            countSub > 0 -> initGroupTab()
                            else -> toastError(R.string.toast_failure)
                        }
                        binding.pbWaiting.hide()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        toastError(R.string.toast_failure)
                        binding.pbWaiting.hide()
                    }
                    Log.e(AppConfig.TAG, "Failed to import batch config", e)
                }
            }
        }
    }

    private val disposables = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        title = getString(R.string.app_name)
        setSupportActionBar(binding.toolbar)

        // Process openUrl from Intent
        intent.getStringExtra("openUrl")?.let { url ->
            if (url.startsWith("http")) {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                try {
                    startActivity(browserIntent)
                } catch (e: Exception) {
                    toast("Cannot open URL: ${e.message}")
                }
            }
        }

        binding.fab.setOnClickListener {
            if (isUpdatingServers) {
                toast("لطفاً منتظر بمانید تا سرورها به‌روزرسانی شوند")
                return@setOnClickListener
            }
            if (mainViewModel.isRunning.value == true) {
                V2RayServiceManager.stopVService(this)
            } else if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN) == VPN) {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    lifecycleScope.launch {
                        startV2Ray()
                    }
                } else {
                    requestVpnPermission.launch(intent)
                }
            } else {
                lifecycleScope.launch {
                    startV2Ray()
                }
            }
        }
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setTestState(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            }
        }

        binding.recyclerView.setHasFixedSize(true)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        } else {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 1)
        }
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)

        // Add Mustafa subscription on startup
        addMustafaSubscription()
        initGroupTab()
        setupViewModel()
        migrateLegacy()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingAction = Action.POST_NOTIFICATIONS
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Process openUrl from new Intent
        intent.getStringExtra("openUrl")?.let { url ->
            if (url.startsWith("http")) {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                try {
                    startActivity(browserIntent)
                } catch (e: Exception) {
                    toast("Cannot open URL: ${e.message}")
                }
            }
        }
    }

    private fun addMustafaSubscription() {
        val appName = getString(R.string.app_name)
        val mustafaUrl = "https://raw.githubusercontent.com/mustafa137608064/subdr/refs/heads/main/users/$appName.php"
        val existingSubscriptions = MmkvManager.decodeSubscriptions()
        if (existingSubscriptions.none { it.second.url == mustafaUrl }) {
            val subscriptionId = Utils.getUuid()
            val subscriptionItem = SubscriptionItem(
                remarks = "$appName Subscription",
                url = mustafaUrl,
                enabled = true
            )
            MmkvManager.encodeSubscription(subscriptionId, subscriptionItem)
            Log.d(AppConfig.TAG, "Added $appName subscription with ID: $subscriptionId")
        } else {
            Log.d(AppConfig.TAG, "$appName subscription already exists")
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
        }
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            if (isRunning) {
                binding.fab.setImageResource(R.drawable.ic_stop_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
                setTestState(getString(R.string.connection_connected))
                binding.layoutTest.isFocusable = true
            } else {
                binding.fab.setImageResource(R.drawable.ic_play_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
                setTestState(getString(R.string.connection_not_connected))
                binding.layoutTest.isFocusable = false
            }
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun migrateLegacy() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = MigrateManager.migrateServerConfig2Profile()
            launch(Dispatchers.Main) {
                if (result) {
                    toast(getString(R.string.migration_success))
                    mainViewModel.reloadServerList()
                }
            }
        }
    }

    private fun initGroupTab() {
        binding.tabGroup.removeOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.removeAllTabs()
        binding.tabGroup.isVisible = false

        val (listId, listRemarks) = mainViewModel.getSubscriptions(this)
        if (listId == null || listRemarks == null) {
            return
        }

        for (it in listRemarks.indices) {
            val tab = binding.tabGroup.newTab()
            tab.text = listRemarks[it]
            tab.tag = listId[it]
            binding.tabGroup.addTab(tab)
        }
        val selectIndex = listId.indexOf(mainViewModel.subscriptionId).takeIf { it >= 0 } ?: (listId.count() - 1)
        binding.tabGroup.selectTab(binding.tabGroup.getTabAt(selectIndex))
        binding.tabGroup.addOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.isVisible = true
    }

    private suspend fun startV2Ray() {
        val selectedServer = MmkvManager.getSelectServer()
        if (selectedServer.isNullOrEmpty()) {
            toast("لطفاً یک سرور را انتخاب کنید یا منتظر اتمام به‌روزرسانی بمانید")
            return
        }
        try {
            if (mainViewModel.isRunning.value == true || isServiceRunning(this, "com.v2ray.ang.service.V2RayVpnService")) {
                V2RayServiceManager.stopVService(this)
                delay(2000)
                if (isServiceRunning(this, "com.v2ray.ang.service.V2RayVpnService")) {
                    toastError("سرویس هنوز در حال اجرا است، لطفاً دوباره تلاش کنید")
                    return
                }
            }
            V2RayServiceManager.startVService(this)
        } catch (e: Exception) {
            toastError("خطا در شروع سرویس VPN: ${e.message}")
            Log.e(AppConfig.TAG, "Failed to start V2Ray service", e)
        }
    }

    private fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true || isServiceRunning(this, "com.v2ray.ang.service.V2RayVpnService")) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(1000)
            startV2Ray()
        }
    }

    private fun updateServerList() {
        binding.pbWaiting.show()
        isUpdatingServers = true
        binding.fab.isEnabled = false

        Api.fetchAllSubscriptions()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ configsList ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val newServers = mutableListOf<String>()
                        configsList.forEach { config ->
                            val (count, countSub) = AngConfigManager.importBatchConfig(config, mainViewModel.subscriptionId, false)
                            if (count > 0 || countSub > 0) {
                                newServers.add(config)
                                Log.d(AppConfig.TAG, "Imported $count servers and $countSub subscriptions")
                            }
                        }
                        if (newServers.isNotEmpty()) {
                            mainViewModel.removeAllServer()
                            newServers.forEach { config ->
                                AngConfigManager.importBatchConfig(config, mainViewModel.subscriptionId, true)
                            }
                            withContext(Dispatchers.Main) {
                                toast(getString(R.string.title_import_config_count, newServers.size))
                                mainViewModel.reloadServerList()
                                initGroupTab()
                                toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
                                mainViewModel.testAllRealPing()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                toastError("خطا: بروزرسانی انجام نشد")
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            toastError("خطا در وارد کردن سرورها: ${e.message}")
                            Log.e(AppConfig.TAG, "Failed to import configs", e)
                        }
                    } finally {
                        withContext(Dispatchers.Main) {
                            binding.pbWaiting.hide()
                            isUpdatingServers = false
                            binding.fab.isEnabled = true
                        carbonaceous
                    }
                }
            }, { error ->
                toastError("خطا در دریافت سرورها: ${error.message}")
                Log.e(AppConfig.TAG, "Error fetching subscriptions: ${error.message}", error)
                binding.pbWaiting.hide()
                isUpdatingServers = false
                binding.fab.isEnabled = true
            })
            .let { disposables.add(it) }
    }

    fun importConfigViaSub(): Boolean {
        try {
            toast(R.string.title_sub_update)
            MmkvManager.decodeSubscriptions().forEach {
                if (TextUtils.isEmpty(it.first) || TextUtils.isEmpty(it.second.remarks) || TextUtils.isEmpty(it.second.url)) {
                    return@forEach
                }
                if (!it.second.enabled) {
                    return@forEach
                }
                val url = Utils.idnToASCII(it.second.url)
                if (!Utils.isValidUrl(url)) {
                    toastError("URL نامعتبر: ${it.second.remarks}")
                    return@forEach
                }
                Log.d(AppConfig.TAG, "Fetching subscription: $url")
                lifecycleScope.launch(Dispatchers.IO) {
                    val configText = try {
                        Utils.getUrlContentWithCustomUserAgent(url)
                    } catch (e: Exception) {
                        launch(Dispatchers.Main) {
                            toastError("\"${it.second.remarks}\" ${getString(R.string.toast_failure)}: ${e.message}")
                        }
                        Log.e(AppConfig.TAG, "Failed to fetch subscription $url: ${e.message}", e)
                        return@launch
                    }
                    try {
                        val (count, countSub) = AngConfigManager.importBatchConfig(configText, it.first, true)
                        launch(Dispatchers.Main) {
                            if (count > 0 || countSub > 0) {
                                toast(getString(R.string.title_import_config_count, count))
                                mainViewModel.reloadServerList()
                                initGroupTab()
                            } else {
                                toastError("هیچ سروری از ${it.second.remarks} وارد نشد")
                            }
                        }
                    } catch (e: Exception) {
                        launch(Dispatchers.Main) {
                            toastError("خطا در وارد کردن سرورها از ${it.second.remarks}: ${e.message}")
                        }
                        Log.e(AppConfig.TAG, "Failed to import configs from $url: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            toastError("خطا در به‌روزرسانی ساب‌اسکریپشن‌ها: ${e.message}")
            Log.e(AppConfig.TAG, "Error updating subscriptions", e)
            return false
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
    }

    override fun onStart() {
        super.onStart()
        if (isServiceRunning(this, "com.v2ray.ang.service.V2RayVpnService")) {
            V2RayServiceManager.stopVService(this)
            lifecycleScope.launch {
                delay(1000)
                mainViewModel.isRunning.value = false
            }
        }
        updateServerList()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }
        R.id.import_clipboard -> {
            importClipboard()
            true
        }
        R.id.import_local -> {
            importConfigLocal()
            true
        }
        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }
        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }
        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }
        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }
        R.id.import_manually_http -> {
            importManually(EConfigType.HTTP.value)
            true
        }
        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }
        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }
        R.id.import_manually_hysteria2 -> {
            importManually(EConfigType.HYSTERIA2.value)
            true
        }
        R.id.export_all -> {
            exportAll()
            true
        }
        R.id.ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllTcping()
            true
        }
        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }
        R.id.service_restart -> {
            restartV2Ray()
            true

        }
        R.id.del_all_config -> {
            delAllConfig()
            true
        }
        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }
        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }
        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }
        R.id.sub_update -> {
            importConfigViaSub()
            true
        }
        R.id.refresh_servers -> {
            if (isUpdatingServers) {
                toast("در حال به‌روزرسانی سرورها، لطفاً صبر کنید")
                true
            } else {
                updateServerList()
                true
            }
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        startActivity(
            Intent()
                .putExtra("createConfigType", createConfigType)
                .putExtra("subscriptionId", mainViewModel.subscriptionId)
                .setClass(this, ServerActivity::class.java)
        )
    }

    private fun importQRcode(): Boolean {
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
        } else {
            pendingAction = Action.IMPORT_QR_CODE_CONFIG
            requestPermissionLauncher.launch(permission)
        }
        return true
    }

    private fun importClipboard(): Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val (count, countSub) = AngConfigManager.importBatchConfig(clipboard, mainViewModel.subscriptionId, true)
                    delay(500L)
                    withContext(Dispatchers.Main) {
                        when {
                            count > 0 -> {
                                toast(getString(R.string.title_import_config_count, count))
                                mainViewModel.reloadServerList()
                            }
                            countSub > 0 -> initGroupTab()
                            else -> toastError(R.string.toast_failure)
                        }
                        binding.pbWaiting.hide()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        toastError(R.string.toast_failure)
                        binding.pbWaiting.hide()
                    }
                    Log.e(AppConfig.TAG, "Failed to import batch config", e)
                }
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }

    private fun exportAll() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                binding.pbWaiting.hide()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun sortByTestResults() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                binding.pbWaiting.hide()
            }
        }
    }

    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            pendingAction = Action.READ_CONTENT_FROM_URI
            chooseFileForCustomConfig.launch(Intent.createChooser(intent, getString(R.string.title_file_chooser)))
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun readContentFromUri(uri: Uri) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    val server = input?.bufferedReader()?.readText()
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                            delay(500L)
                            withContext(Dispatchers.Main) {
                                when {
                                    count > 0 -> {
                                        toast(getString(R.string.title_import_config_count, count))
                                        mainViewModel.reloadServerList()
                                    }
                                    countSub > 0 -> initGroupTab()
                                    else -> toastError(R.string.toast_failure)
                                }
                                binding.pbWaiting.hide()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                toastError(R.string.toast_failure)
                                binding.pbWaiting.hide()
                            }
                            Log.e(AppConfig.TAG, "Failed to import batch config", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_telegram_channel -> {
                val telegramUrl = "tg:resolve?domain=v2plus_v2ray_vpn"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(telegramUrl))
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    toast("Cannot open URL: ${e.message}")
                }
            }
            R.id.nav_support_team -> {
                val supportUrl = "tg:resolve?domain=v2plus_admin"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(supportUrl))
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    toast("Cannot open URL: ${e.message}")
                }
            }
            R.id.nav_check_update -> {
                val updateUrl = "http://v2plusapp.wuaze.com/update-1-9-46/"
                WebViewDialogFragment.newInstance(updateUrl).show(supportFragmentManager, "WebViewDialog")
            }
            R.id.nav_tutorial_web -> {
                val tutorialUrl = "http://v2plusapp.wuaze.com/tutorial/"
                WebViewDialogFragment.newInstance(tutorialUrl).show(supportFragmentManager, "WebViewDialog")
            }
            R.id.nav_report_problem -> {
                val reportUrl = "http://v2plusapp.wuaze.com/report/"
                WebViewDialogFragment.newInstance(reportUrl).show(supportFragmentManager, "WebViewDialog")
            }
            R.id.nav_about_us -> {
                val aboutusUrl = "http://v2plusapp.wuaze.com/about/"
                WebViewDialogFragment.newInstance(aboutusUrl).show(supportFragmentManager, "WebViewDialog")
            }
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceRunning(this, "com.v2ray.ang.service.V2RayVpnService")) {
            V2RayServiceManager.stopVService(this)
            lifecycleScope.launch {
                delay(1000)
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
        disposables.clear()
    }

    private fun isServiceRunning(context: Context, serviceClassName: String): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClassName == service.service.className) {
                return true
            }
        }
        return false
    }
}
