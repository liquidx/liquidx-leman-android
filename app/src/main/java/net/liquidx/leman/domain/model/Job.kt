package net.liquidx.leman.domain.model

/**
 * A gateway scheduled job (jobs-tab design). Server is the system of record;
 * `id` IS the server job id. Timestamps are epoch millis, parsed from the
 * wire's ISO-8601 strings; null when absent or unparseable.
 */
data class Job(
    val id: String,
    val name: String,
    val prompt: String,
    /** Server-normalized schedule ("0 7 * * *", "every 120m", …). */
    val scheduleDisplay: String,
    val enabled: Boolean,
    val nextRunAt: Long?,
    val lastRunAt: Long?,
    /** "ok", "error", or null when the job has never run. */
    val lastStatus: String?,
    val lastError: String?,
    val runsCompleted: Int,
)
