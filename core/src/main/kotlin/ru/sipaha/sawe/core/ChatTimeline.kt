package ru.sipaha.sawe.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One row in the rendered chat: either a date separator or a message. */
sealed interface ChatItem {
    data class DateSeparator(val epochDay: Long) : ChatItem
    data class Message(val entry: EntrySummary) : ChatItem
}

/**
 * Insert date-separator rows into a CHRONOLOGICAL (oldest-first) entry list:
 * a leading separator before the first timestamped entry, and one whenever
 * the local-zone calendar date changes between consecutive timestamped
 * entries. Entries with a null or non-positive `createdMs` never trigger a
 * separator (we don't fabricate dates for pre-feature history, and the server
 * maps absent timestamps to null / omitted — but a zero/negative value is
 * also treated as "no time captured" consistent with the ">0 = real" rule).
 * The caller reverses the result for a `reverseLayout` list.
 */
fun withDateSeparators(entries: List<EntrySummary>, zone: ZoneId): List<ChatItem> {
    val out = ArrayList<ChatItem>(entries.size + 4)
    var lastDate: LocalDate? = null
    for (entry in entries) {
        val date = entry.createdMs?.takeIf { it > 0 }?.let {
            Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
        }
        if (date != null && date != lastDate) {
            out.add(ChatItem.DateSeparator(date.toEpochDay()))
            lastDate = date
        }
        out.add(ChatItem.Message(entry))
    }
    return out
}
