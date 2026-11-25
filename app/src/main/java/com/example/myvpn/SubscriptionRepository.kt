package com.example.myvpn

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.MutableStateFlow

internal object SubscriptionRepository {
    private val lock = Any()
    private val refreshing = AtomicBoolean(false)
    val changed = MutableStateFlow(0L)

    fun list(context: Context): List<SavedSubscription> = synchronized(lock) { ProtectedStore(context).read() }

    private fun edit(context: Context, change: (List<SavedSubscription>) -> List<SavedSubscription>) = synchronized(lock) {
        val store = ProtectedStore(context)
        val latest = store.read()
        val updated = change(latest)
        if (updated != latest) {
            try { store.write(updated) }
            catch (_: Exception) { throw SubscriptionFailure("Не удалось сохранить список подписок на телефоне.") }
            changed.value++
        }
        updated
    }

    fun add(context: Context, link: String, label: String, sendId: Boolean): Int {
        val host = SubscriptionClient.requireHttps(link).host
        if (list(context).any { it.link == link }) throw SubscriptionFailure("Эта подписка уже добавлена.")
        val item = fetch(context, SavedSubscription(label.ifBlank { host }, link, sendId, emptyList()))
        edit(context) { current ->
            if (current.any { it.link == link }) throw SubscriptionFailure("Эта подписка уже добавлена.")
            current + item
        }
        return item.nodes.size
    }

    fun remove(context: Context, link: String) { edit(context) { list -> list.filterNot { it.link == link } } }

    fun refresh(context: Context): String {
        if (!refreshing.compareAndSet(false, true)) return "Подписки уже обновляются."
        try {
            val snapshot = list(context)
            val updates = mutableMapOf<String, SavedSubscription>()
            var failed = 0
            var first: String? = null
            for (entry in snapshot) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                try { updates[entry.link] = fetch(context, entry) }
                catch (error: Exception) {
                    failed++
                    if (first == null) first = (error as? SubscriptionFailure)?.message
                }
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            edit(context) { latest -> latest.map { old ->
                // Compare the identity of the saved entry, not its cached node list.
                val prior = snapshot.firstOrNull { it.link == old.link }
                if (prior != null && prior.label == old.label && prior.sendDeviceId == old.sendDeviceId) updates[old.link] ?: old else old
            } }
            return if (failed == 0) "Подписки обновлены." else
                "Не обновилось: $failed. Предыдущий список сохранён." + first?.let { "\n$it" }.orEmpty()
        } finally { refreshing.set(false) }
    }

    private fun fetch(context: Context, sub: SavedSubscription): SavedSubscription {
        val id = if (sub.sendDeviceId) Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: throw SubscriptionFailure("Android не предоставил ID клиента.") else null
        val nodes = SubscriptionClient.fetch(sub.link, id, Build.VERSION.RELEASE, Build.MODEL)
        return SavedSubscription(sub.label, sub.link, sub.sendDeviceId, nodes)
    }
}

class SubscriptionRefreshJob : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = CoroutineScope(Dispatchers.Main).launch {
            runInterruptible(Dispatchers.IO) { runCatching { SubscriptionRepository.refresh(applicationContext) } }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    companion object {
        private const val ID = 12391
        internal fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(ID) != null) return
            scheduler.schedule(JobInfo.Builder(ID, ComponentName(context, SubscriptionRefreshJob::class.java))
                .setPersisted(true).setPeriodic(TimeUnit.HOURS.toMillis(6))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
        }
    }
}
