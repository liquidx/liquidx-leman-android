package net.liquidx.leman.ui.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DayBucket { Today, Yesterday, Earlier }

/**
 * All TODAY/YESTERDAY bucketing uses the device-local calendar on
 * lastActiveAt and re-buckets at local midnight (spec 04).
 */
object TimeFormat {
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val monthDayFormat = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT)

    fun clock(nowMillis: Long, zone: ZoneId): String =
        clockFormat.format(Instant.ofEpochMilli(nowMillis).atZone(zone))

    fun bucket(epochMillis: Long, nowMillis: Long, zone: ZoneId): DayBucket {
        val day = LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        return when {
            !day.isBefore(today) -> DayBucket.Today
            day == today.minusDays(1) -> DayBucket.Yesterday
            else -> DayBucket.Earlier
        }
    }

    /** `now` under a minute; HH:mm today/yesterday; `jul 11` earlier. */
    fun timeLabel(epochMillis: Long, nowMillis: Long, zone: ZoneId): String = when {
        nowMillis - epochMillis < 60_000 -> "now"
        bucket(epochMillis, nowMillis, zone) == DayBucket.Earlier ->
            monthDay(epochMillis, zone)
        else -> clock(epochMillis, zone)
    }

    fun monthDay(epochMillis: Long, zone: ZoneId): String =
        monthDayFormat.format(Instant.ofEpochMilli(epochMillis).atZone(zone)).lowercase(Locale.ROOT)

    /** Future-facing (jobs' next run): `HH:mm` on today's local date, else `jul 21 07:00`. */
    fun upcomingLabel(epochMillis: Long, nowMillis: Long, zone: ZoneId): String {
        val day = LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val time = clock(epochMillis, zone)
        return if (day == today) time else "${monthDay(epochMillis, zone)} $time"
    }
}
