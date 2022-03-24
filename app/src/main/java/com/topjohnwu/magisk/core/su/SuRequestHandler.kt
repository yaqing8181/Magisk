package com.topjohnwu.magisk.core.su

import android.content.Intent
import android.content.pm.PackageManager
import com.topjohnwu.magisk.BuildConfig
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.magiskdb.PolicyDao
import com.topjohnwu.magisk.core.model.su.SuPolicy
import com.topjohnwu.magisk.core.model.su.toPolicy
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.Closeable
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class SuRequestHandler(
    private val pm: PackageManager,
    private val policyDB: PolicyDao
) : Closeable {

    private lateinit var output: DataOutputStream
    lateinit var policy: SuPolicy
        private set

    // Return true to indicate undetermined policy, require user interaction
    suspend fun start(intent: Intent): Boolean {
        if (!init(intent))
            return false

        // Never allow com.topjohnwu.magisk (could be malware)
        if (policy.packageName == BuildConfig.APPLICATION_ID) {
            Shell.cmd("(pm uninstall ${BuildConfig.APPLICATION_ID})& >/dev/null 2>&1").exec()
            return false
        }

        when (Config.suAutoResponse) {
            Config.Value.SU_AUTO_DENY -> {
                respond(SuPolicy.DENY, 0)
                return false
            }
            Config.Value.SU_AUTO_ALLOW -> {
                respond(SuPolicy.ALLOW, 0)
                return false
            }
        }

        return true
    }

    override fun close() {
        if (::output.isInitialized)
            runCatching { output.close() }
    }

    private class SuRequestError : IOException()

    private suspend fun init(intent: Intent) = withContext(Dispatchers.IO) {
        try {
            val name = intent.getStringExtra("fifo") ?: throw SuRequestError()
            val uid = intent.getIntExtra("uid", -1).also { if (it < 0) throw SuRequestError() }
            output = DataOutputStream(FileOutputStream(name).buffered())
            policy = uid.toPolicy(pm)
            true
        } catch (e: Exception) {
            when (e) {
                is IOException, is PackageManager.NameNotFoundException -> {
                    Timber.e(e)
                    close()
                    false
                }
                else -> throw e  // Unexpected error
            }
        }
    }

    suspend fun respond(action: Int, time: Int) {
        val until = if (time > 0)
            TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) +
                TimeUnit.MINUTES.toSeconds(time.toLong())
        else
            time.toLong()

        policy.policy = action
        policy.until = until

        withContext(Dispatchers.IO) {
            try {
                output.writeInt(policy.policy)
                output.flush()
            } catch (e: IOException) {
                Timber.e(e)
            } finally {
                close()
                if (until >= 0)
                    policyDB.update(policy)
            }
        }
    }
}
