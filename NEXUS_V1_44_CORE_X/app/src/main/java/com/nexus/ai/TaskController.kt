package com.nexus.ai

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class TaskController(private val context: Context, private val journal: AgentJournal) {
    fun enqueue(objective: String): String = NexusBackgroundWorker.enqueue(context, objective).toString()

    fun cancel(workId: String): Boolean = runCatching {
        WorkManager.getInstance(context).cancelWorkById(UUID.fromString(workId))
        journal.markCancelledByWorkId(workId)
        true
    }.getOrDefault(false)

    fun observe(workId: String): Flow<WorkInfo.State?> =
        WorkManager.getInstance(context)
            .getWorkInfoByIdFlow(UUID.fromString(workId))
            .map { it?.state }
}
