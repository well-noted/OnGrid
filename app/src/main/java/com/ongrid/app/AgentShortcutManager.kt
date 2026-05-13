package com.ongrid.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.app.Person
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
                val icon = IconCompat.createWithBitmap(buildAgentBitmap(agent))
                val person = Person.Builder()
                    .setName(agent.name)
                    .setIcon(icon)
                    .setKey("agent_${agent.id}")
                    .build()
                ShortcutInfoCompat.Builder(context, "agent_${agent.id}")
                    .setShortLabel(agent.name)
                    .setLongLabel(agent.name)
                    .setIcon(icon)
                    .setPerson(person)
                    .setLongLived(true)
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

    /**
     * Draws a circular avatar with the agent's accent color as background
     * and the first letter of their name in white — the same visual language
     * used in AgentListCard in the UI.
     */
    private fun buildAgentBitmap(agent: AgentEntity): Bitmap {
        val size = 108
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f

        // Filled circle in agent's color (fall back to Material purple)
        val bgColor = if (agent.color != 0) agent.color else 0xFF6750A4.toInt()
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cx, cx, bgPaint)

        // First initial, centered
        val initial = agent.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = size * 0.44f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        // Vertically center: baseline = center - midpoint of ascent+descent
        val textY = cx - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(initial, cx, textY, textPaint)

        return bitmap
    }
}
