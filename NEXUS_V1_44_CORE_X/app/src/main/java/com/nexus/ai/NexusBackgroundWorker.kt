package com.nexus.ai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Data
import androidx.work.Constraints
import androidx.work.NetworkType
import java.util.UUID

class NexusBackgroundWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val objective = inputData.getString(KEY_OBJECTIVE).orEmpty()
        val taskId = inputData.getString(KEY_TASK_ID) ?: UUID.randomUUID().toString()
        val workId = id.toString()
        val journal = AgentJournal(applicationContext)
        val settings = NexusSettings(applicationContext)
        journal.createTask(taskId, objective, workId)
        journal.add(taskId, "BACKGROUND_STARTED", "Worker iniciado: $workId")

        if (isStopped) {
            journal.setTaskState(taskId, TaskState.CANCELLED)
            journal.add(taskId, "CANCELLED", "Worker interrompido antes da execução.")
            return Result.failure()
        }

        return try {
            val registry = ToolRegistryFactory.create(applicationContext, includePlugins = true)
            val androidHub = AndroidToolHub(applicationContext)
            val agent = NexusAgent(
                settings.provider(), registry, journal, webSearch = false, backgroundExecution = true,
                androidStateObservationAdapter = androidHub.stateObservationAdapter(),
                androidActionSelector = AndroidActionSelector(androidHub.actionRegistry(), journal)
            )
            journal.add(taskId, "RESUMED", "Tarefa retomada pelo WorkManager.")
            val result = agent.run(listOf(ChatMessage(MessageRole.USER, objective)), taskId)
            if (isStopped) {
                journal.setTaskState(taskId, TaskState.CANCELLED)
                journal.add(taskId, "CANCELLED", "Worker cancelado pelo sistema/usuário.")
                Result.failure()
            } else {
                journal.add(taskId, "BACKGROUND_FINISHED", result.text)
                Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            journal.setTaskState(taskId, TaskState.CANCELLED)
            journal.add(taskId, "CANCELLED", "Execução cancelada.")
            Result.failure()
        } catch (e: Exception) {
            journal.setTaskState(taskId, TaskState.FAILED)
            journal.add(taskId, "BACKGROUND_FAILED", e.message ?: "Erro desconhecido")
            Result.failure()
        }
    }

    companion object {
        const val KEY_OBJECTIVE = "objective"
        const val KEY_TASK_ID = "task_id"

        fun enqueue(context: Context, objective: String): UUID {
            val taskId = UUID.randomUUID().toString()
            val settings = NexusSettings(context)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (settings.gatewayEndpoint.isBlank()) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED)
                .build()
            val input = Data.Builder().putString(KEY_OBJECTIVE, objective).putString(KEY_TASK_ID, taskId).build()
            val request = OneTimeWorkRequestBuilder<NexusBackgroundWorker>()
                .setInputData(input)
                .setConstraints(constraints)
                .addTag("nexus-task")
                .build()
            WorkManager.getInstance(context).enqueue(request)
            AgentJournal(context).createTask(taskId, objective, request.id.toString())
            AgentJournal(context).add(taskId, "QUEUED", "Tarefa enviada ao WorkManager.")
            return request.id
        }
    }
}
