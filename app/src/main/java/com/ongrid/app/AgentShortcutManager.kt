package com.ongrid.app

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.ongrid.app.data.local.AgentEntity
import com.ongrid.app.data.local.AgentStatus

class AgentShortcutManager(private val context: Context) {

    fun sync(agents: List<AgentEntity>) {
        val shortcuts = agents
            .filter { it.status == AgentStatus.ACTIVE }
            .take(4)
            .map { agent ->
                ShortcutInfoCompat.Builder(context, "agent_${agent.id}")
                    .setShortLabel(agent.name)
                    .setLongLabel("Share to ${agent.name}")
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(
                        Intent(context, MainActivity::class.java).apply {
                            action = Intent.ACTION_SEND
                            type = "text/plain"
                            putExtra("target_agent_id", agent.id)
                        }
                    )
                    .setCategories(setOf("androidx.core.content.pm.SHORTCUT_CATEGORY_SHARE"))
                    .build()
            }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }
}
