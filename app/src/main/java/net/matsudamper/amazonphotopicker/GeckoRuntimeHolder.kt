package net.matsudamper.amazonphotopicker

import android.content.Context
import androidx.annotation.MainThread
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/** GeckoRuntime はプロセスに1つしか作れないため共有する */
object GeckoRuntimeHolder {
    private var runtime: GeckoRuntime? = null

    @MainThread
    fun get(context: Context): GeckoRuntime {
        val current = runtime
        if (current != null) return current
        val created = GeckoRuntime.create(
            context.applicationContext,
            GeckoRuntimeSettings.Builder()
                .forceUserScalableEnabled(true)
                .consoleOutput(BuildConfig.DEBUG)
                .build(),
        )
        runtime = created
        return created
    }
}
