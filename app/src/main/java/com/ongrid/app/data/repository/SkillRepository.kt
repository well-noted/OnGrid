package com.ongrid.app.data.repository

import android.content.Context
import android.net.Uri
import com.ongrid.app.data.local.SkillDao
import com.ongrid.app.data.local.SkillEntity
import kotlinx.coroutines.flow.Flow
import java.util.zip.ZipInputStream

class SkillRepository(private val dao: SkillDao) {
    val allSkills: Flow<List<SkillEntity>> = dao.allSkills()

    suspend fun importFromUri(context: Context, uri: Uri): SkillEntity? {
        val displayName = context.contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                else null
            } ?: return null

        val ext = displayName.substringAfterLast('.', "").lowercase()
        val fallbackName = displayName.substringBeforeLast('.')

        val rawText: String = when (ext) {
            "skill", "zip" -> readSkillMdFromZip(context, uri)
            else -> context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
        } ?: return null

        val skill = parseSkillText(rawText, fallbackName)
        dao.insert(skill)
        return skill
    }

    suspend fun remove(id: String) = dao.delete(id)

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Opens a zip/skill archive and returns the text of the first SKILL.md found
     * at root level or exactly one directory deep.
     */
    private fun readSkillMdFromZip(context: Context, uri: Uri): String? {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        ZipInputStream(inputStream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                // Accept SKILL.md at root or one level deep (e.g. "myfolder/SKILL.md")
                val segments = name.trimEnd('/').split('/')
                if (!entry.isDirectory &&
                    segments.last().equals("SKILL.md", ignoreCase = true) &&
                    segments.size <= 2
                ) {
                    return zip.bufferedReader().readText()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return null
    }

    /**
     * Parses a markdown document that may start with a YAML frontmatter block
     * delimited by `---` lines.
     *
     * Returns a [SkillEntity] with:
     * - `name` and `description` from frontmatter `name:` / `description:` fields if present,
     *   otherwise [fallbackName] / first non-blank line of the body.
     * - `content` = the body text with frontmatter stripped.
     */
    private fun parseSkillText(text: String, fallbackName: String): SkillEntity {
        val lines = text.lines()

        // Detect YAML frontmatter: document must start with "---"
        var frontmatterName: String? = null
        var frontmatterDescription: String? = null
        var bodyStartIndex = 0

        if (lines.firstOrNull()?.trim() == "---") {
            val closeIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (closeIndex >= 0) {
                val fmLines = lines.subList(1, closeIndex + 1)
                for (line in fmLines) {
                    val colon = line.indexOf(':')
                    if (colon < 0) continue
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                        .removeSurrounding("\"")
                        .removeSurrounding("'")
                    when (key) {
                        "name" -> frontmatterName = value.ifBlank { null }
                        "description" -> frontmatterDescription = value.ifBlank { null }
                    }
                }
                // Body starts after the closing "---"
                bodyStartIndex = closeIndex + 2 // +1 for the skipped first line, +1 for close
            }
        }

        val body = lines.drop(bodyStartIndex).joinToString("\n").trimStart()
        val name = frontmatterName ?: fallbackName
        val description = frontmatterDescription
            ?: body.lines().firstOrNull { it.isNotBlank() }?.take(120)
            ?: ""

        return SkillEntity(name = name, description = description, content = body)
    }
}
