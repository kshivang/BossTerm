package ai.rever.bossterm.compose.settings

/**
 * Utility functions for system information retrieval.
 */
object SystemInfoUtils {

    /**
     * Get total system physical memory in megabytes.
     * Uses com.sun.management.OperatingSystemMXBean when available,
     * falls back to JVM max memory * 4 as a rough estimate.
     *
     * @return System memory in MB
     */
    fun getSystemMemoryMb(): Int {
        val runtime = Runtime.getRuntime()
        val maxMemoryMb = (runtime.maxMemory() / (1024 * 1024)).toInt()

        return try {
            val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            if (osBean is com.sun.management.OperatingSystemMXBean) {
                ((osBean as com.sun.management.OperatingSystemMXBean).totalPhysicalMemorySize / (1024 * 1024)).toInt()
            } else {
                // Fallback: estimate system memory as 4x JVM max (rough heuristic)
                maxMemoryMb * 4
            }
        } catch (e: Exception) {
            // Fallback if com.sun.management is not available
            maxMemoryMb * 4
        }
    }

    /**
     * Format memory size in MB to human-readable string (MB or GB).
     * Practical terminal GPU caches won't exceed GB range.
     *
     * @param mb Memory size in megabytes
     * @return Formatted string (e.g., "256 MB", "2.5 GB")
     */
    fun formatMemorySize(mb: Int): String {
        return when {
            mb >= 1024 -> "%.1f GB".format(mb / 1024f)
            else -> "$mb MB"
        }
    }
}
