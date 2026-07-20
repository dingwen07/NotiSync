package net.extrawdw.apps.notisync.ui

import android.content.Context
import android.widget.Toast
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.R

/** Launch a user-confirmed trust change from the application scope and surface durable-write failures. */
internal fun AppGraph.launchDurableTrustAction(
    context: Context,
    mutation: () -> Unit,
) {
    val appContext = context.applicationContext
    durableTrustMutations.launch(
        onFailure = {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.trust_change_failed),
                Toast.LENGTH_LONG,
            ).show()
        },
        mutation = mutation,
    )
}
