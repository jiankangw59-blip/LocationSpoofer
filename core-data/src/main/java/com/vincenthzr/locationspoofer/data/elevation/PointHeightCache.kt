package com.vincenthzr.locationspoofer.data.elevation

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Private, disposable surface-model cache, not a database of the handset's locations. */
class PointHeightCache(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        private const val LIMIT = 64
        private const val TTL = 180L * 24 * 60 * 60 * 1000
    }
    private fun file(key: String): File {
        val bytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.US_ASCII))
        return File(dir, bytes.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) } + ".dem")
    }
    @Synchronized fun get(key: String): TargetElevation? = try {
        val f = file(key)
        val age = now() - f.lastModified()
        if (!f.isFile || f.length() !in 1L..100L || age !in 0L..TTL) null else {
            val fields = f.readText().split(',')
            val msl = fields.getOrNull(0)?.toDoubleOrNull()
            val h = fields.getOrNull(1)?.toDoubleOrNull()
            if (fields.size == 2 && msl != null && h != null && msl in -600.0..9000.0 &&
                h - msl in -200.0..200.0) TargetElevation(msl, h) else null
        }
    } catch (_: IOException) { null }

    @Synchronized fun put(key: String, estimate: TargetElevation) {
        if (estimate.surfaceMslMeters !in -600.0..9000.0 ||
            estimate.ellipsoidMeters - estimate.surfaceMslMeters !in -200.0..200.0) return
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val target = file(key)
            val tmp = File.createTempFile("new-", ".tmp", dir)
            try {
                tmp.writeText("${estimate.surfaceMslMeters},${estimate.ellipsoidMeters}")
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                target.setLastModified(now())
            } finally { tmp.delete() }
            dir.listFiles { f -> f.name.endsWith(".dem") }?.sortedByDescending { it.lastModified() }
                ?.drop(LIMIT)?.forEach { it.delete() }
        } catch (_: IOException) { /* inability to cache must not discard a good online estimate */ }
    }
}
