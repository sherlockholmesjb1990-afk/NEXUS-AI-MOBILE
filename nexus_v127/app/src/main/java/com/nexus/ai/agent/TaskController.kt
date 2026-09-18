package com.nexus.ai.agent

import android.content.Context
import androidx.work.WorkManager
import java.util.UUID

class TaskController(private val context: Context) {
    fun cancel(workId: UUID) {
        WorkManager.getInstance(context).cancelWorkById(workId)
    }

    fun cancelByTag(tag: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tag)
    }
}
