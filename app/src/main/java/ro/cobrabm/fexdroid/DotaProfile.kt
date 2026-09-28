package ro.cobrabm.fexdroid

import java.io.File

/**
 * Dota 2's video settings for phones: what the game's own Settings › Video page writes to
 * `userdata/<account>/570/local/cfg/video.txt`, set to the cheapest values. The file is per
 * machine (not in Steam Cloud), so a player's PC keeps its settings.
 *
 * Under the emulator the game is limited by its main thread on a phone that throttles when
 * hot (NOTES N-036): shadows, particles and the extra lighting passes cost CPU time per frame
 * (draw calls, animation) and GPU heat, which lowers the CPU's frequency limit further.
 *
 * Anti-aliasing is left as the player set it: the game offers FidelityFX Super Resolution
 * only with anti-aliasing on.
 *
 * The values the player had are kept in `video.txt.fexdroid-orig` and put back when the
 * profile is switched off.
 */
object DotaProfile {
    private const val BACKUP = "video.txt.fexdroid-orig"

    /** Numbers as the game writes them; 0/1 become false/true where the file has booleans. */
    private val PERFORMANCE = linkedMapOf(
        "setting.useadvanced" to "1", // Otherwise the basic quality slider decides.
        "setting.cpu_level" to "0",
        "setting.cl_particle_fallback_base" to "4",
        "setting.cl_particle_fallback_multiplier" to "4",
        "setting.cl_globallight_shadow_mode" to "0",
        "setting.r_deferred_height_fog" to "0",
        "setting.r_deferred_simple_light" to "0",
        "setting.r_deferred_additive_pass" to "0",
        "setting.r_deferred_specular" to "0",
        "setting.r_deferred_specular_bloom" to "0",
        "setting.r_ssao" to "0",
        "setting.r_dota_normal_maps" to "0",
        "setting.r_dota_allow_parallax_mapping" to "0",
        "setting.r_dota_allow_wind_on_trees" to "0",
        "setting.r_dota_bloom_compute_shader" to "0",
        "setting.r_depth_of_field" to "0",
        "setting.r_grass_quality" to "0",
        "setting.r_dashboard_render_quality" to "0",
        "setting.dota_portrait_animate" to "0",
        "setting.dota_ambient_creatures" to "0",
        "setting.dota_ambient_cloth" to "0",
        "setting.dota_cheap_water" to "1",
        "setting.shaderquality" to "0",
        "setting.r_texture_stream_mip_bias" to "2",
        "setting.mat_vsync" to "0",
    )

    private val line = Regex("^(\\s*)\"([^\"]+)\"(\\s+)\"([^\"]*)\"\\s*$")

    /** Every account's video.txt (the game writes it at its first start). */
    private fun files(env: LinuxEnv): List<File> =
        File(env.home, ".local/share/Steam/userdata").listFiles().orEmpty()
            .map { File(it, "570/local/cfg/video.txt") }.filter { it.isFile }

    /** Called before a session starts. Returns what was done, for the log; null: nothing. */
    fun apply(env: LinuxEnv, enabled: Boolean): String? {
        var changed = 0
        var restored = 0
        for (f in files(env)) {
            val backup = File(f.parentFile, BACKUP)
            if (!enabled) {
                if (backup.isFile) {
                    backup.copyTo(f, overwrite = true)
                    backup.delete()
                    restored++
                }
                continue
            }
            if (!backup.isFile) f.copyTo(backup)
            val seen = HashSet<String>()
            var edits = 0
            val out = f.readLines().map { l ->
                val m = line.matchEntire(l) ?: return@map l
                val (indent, key, gap, old) = m.destructured
                val want = PERFORMANCE[key] ?: return@map l
                seen += key
                val value = if (old == "true" || old == "false") (if (want == "0") "false" else "true") else want
                if (value == old) l else { edits++; "$indent\"$key\"$gap\"$value\"" }
            }.toMutableList()
            // Keys the file does not have yet go before the closing brace.
            val close = out.indexOfLast { it.trim() == "}" }
            if (close >= 0) {
                val missing = PERFORMANCE.filterKeys { it !in seen }.map { (k, v) -> "\t\"$k\"\t\t\"$v\"" }
                out.addAll(close, missing)
                edits += missing.size
            }
            if (edits > 0) {
                val tmp = File(f.parentFile, "video.txt.fexdroid-new")
                tmp.writeText(out.joinToString("\n", postfix = "\n"))
                if (tmp.renameTo(f)) changed++
            }
        }
        return when {
            changed > 0 -> "Dota 2: the performance profile was applied to the video settings."
            restored > 0 -> "Dota 2: the video settings from before the performance profile were put back."
            else -> null
        }
    }
}
