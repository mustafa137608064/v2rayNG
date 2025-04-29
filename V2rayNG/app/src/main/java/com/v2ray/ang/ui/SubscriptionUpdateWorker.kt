package com.v2ray.ang.ui

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SubscriptionUpdateWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val defaultSubUrl = "https://raw.githubusercontent.com/mustafa137608064/subdr/refs/heads/main/users/mustafa.php"
            val subId = MmkvManager.getSubscriptionIdByUrl(defaultSubUrl)
            if (subId != null) {
                val viewModel = MainViewModel()
                val count = viewModel.updateConfigViaSub(subId)
                if (count > 0) {
                    Result.success()
                } else {
                    Result.failure()
                }
            } else {
                Result.failure()
            }
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
