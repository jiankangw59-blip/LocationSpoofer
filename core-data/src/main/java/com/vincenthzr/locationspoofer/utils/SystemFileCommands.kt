package com.vincenthzr.locationspoofer.utils

/** Root is used for diagnostics and one-time legacy cleanup, never configuration delivery. */
internal object SystemFileCommands {
    private const val ROOT = "/proc/1/root"
    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

    fun read(paths: List<String>): String {
        require(paths.isNotEmpty() && paths.all { it.startsWith("/data/") && ".." !in it.split('/') })
        return paths.joinToString(" || ") { "cat ${quote(ROOT + it)} 2>/dev/null" }
    }

    fun removeLegacyConfigs(): String = """
        set -e
        rm -f $ROOT/data/data/com.suseoaa.locationspoofer.fork/files/locationspoofer_config.json
    """.trimIndent()
}
