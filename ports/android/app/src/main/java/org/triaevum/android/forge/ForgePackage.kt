package org.triaevum.android.forge

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.json.JSONException
import org.json.JSONObject
import org.triaevum.android.BuildConfig

/**
 * The APK's Forge package: catalog, recipes, resources, precomputed tables and shader corpus,
 * stored as assets under [ASSET_ROOT]. Forge reads them as files, so they are copied once per
 * installed APK into the app's internal storage. An unchanged portable corpus can be reused
 * across signed updates. The catalog's hashes verify every file Forge uses. The APK's libraries stay where Android installed them (FORGE_SPEC.md 4.4, point 6).
 */
object ForgePackage {
    const val ASSET_ROOT = "forge-package"
    private const val STAMP = ".apk-stamp"
    private const val INVENTORY = "forge-package.json"
    private const val PORTABLE_PACK = "forge/shader-corpus/portable.o3ps"
    private const val MAX_INVENTORY_BYTES = 1 shl 20

    /** True when this APK carries a Forge package (developer APKs may not). */
    fun available(context: Context): Boolean =
        context.assets.list(ASSET_ROOT)?.contains("recipes") == true

    fun hasPipelineRecipes(context: Context): Boolean =
        context.assets.list("$ASSET_ROOT/forge/shader-corpus")
            ?.any { it.startsWith("pipelines-") && it.endsWith(".json") } == true


    /**
     * The package folder for this APK, copied first when the APK changed. Serialized: the
     * installer's inspection and the service's jobs may ask at once right after an APK update.
     */
    @Synchronized
    @Throws(IOException::class)
    fun prepare(context: Context): File {
        val target = File(context.filesDir, ASSET_ROOT)
        val stamp = apkStamp(context)
        val stampFile = File(target, STAMP)
        if (stampFile.isFile && stampFile.readText() == stamp) return target
        val staging = File(context.filesDir, ".$ASSET_ROOT-new")
        if (staging.exists() && !staging.deleteRecursively()) throw IOException("Cannot clear $staging")
        val reusedPack = BuildConfig.VERIFY_FORGE_PACKAGE && reusePortablePack(context.assets, target, staging)
        copyTree(context.assets, ASSET_ROOT, staging, reusedPack)
        if (BuildConfig.VERIFY_FORGE_PACKAGE) {
            try {
                requireSigned(staging)
            } catch (error: IOException) {
                if (!reusedPack || staging.exists()) throw error
                // The old copy might be damaged. The APK remains the fallback source of truth.
                copyTree(context.assets, ASSET_ROOT, staging)
                requireSigned(staging)
            }
        }
        File(staging, STAMP).writeText(stamp)
        if (target.exists() && !target.deleteRecursively()) throw IOException("Cannot replace $target")
        if (!staging.renameTo(target)) throw IOException("Cannot publish $target")
        return target
    }

    /**
     * A signed release refuses a Forge package whose publisher signature or file hashes do not
     * verify (the Rust core, ForgeNative.verifyPackage); nothing is published then.
     */
    @Throws(IOException::class)
    private fun requireSigned(root: File) {
        val answer = JSONObject(String(ForgeNative.verifyPackage(root.absolutePath.toByteArray()), Charsets.UTF_8))
        if (answer.optString("status") != "signed") {
            root.deleteRecursively()
            throw IOException("Forge package refused: " + answer.optString("message", "not signed"))
        }
    }

    private fun apkStamp(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${info.longVersionCode}:${info.lastUpdateTime}"
    }

    private fun reusePortablePack(assets: AssetManager, target: File, staging: File): Boolean {
        val previousInventory = File(target, INVENTORY)
        val previousPack = File(target, PORTABLE_PACK)
        if (!previousPack.isFile || previousInventory.length() !in 1L..MAX_INVENTORY_BYTES.toLong()) return false
        return try {
            val previous = portablePackIdentity(JSONObject(previousInventory.readText())) ?: return false
            val current = assets.open("$ASSET_ROOT/$INVENTORY").use { input ->
                val bytes = ByteArray(MAX_INVENTORY_BYTES + 1)
                var length = 0
                while (length < bytes.size) {
                    val count = input.read(bytes, length, bytes.size - length)
                    if (count < 0) break
                    length += count
                }
                if (length > MAX_INVENTORY_BYTES) return false
                portablePackIdentity(JSONObject(String(bytes, 0, length, Charsets.UTF_8)))
            }
            if (previous != current || previousPack.length() != previous.second) return false
            val destination = File(staging, PORTABLE_PACK)
            Files.createDirectories(destination.toPath().parent)
            Files.createLink(destination.toPath(), previousPack.toPath())
            true
        } catch (_: IOException) {
            false
        } catch (_: JSONException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    private fun portablePackIdentity(inventory: JSONObject): Pair<String, Long>? {
        val files = inventory.getJSONArray("files")
        for (i in 0 until files.length()) {
            val entry = files.getJSONObject(i)
            if (entry.optString("path") == PORTABLE_PACK && entry.optString("role") == "portable_shader_corpus") {
                return entry.getString("sha256") to entry.getLong("bytes")
            }
        }
        return null
    }

    private fun copyTree(assets: AssetManager, path: String, destination: File, reusedPack: Boolean = false) {
        if (reusedPack && path == "$ASSET_ROOT/$PORTABLE_PACK") return
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(path).use { input -> destination.outputStream().use { input.copyTo(it, 1 shl 20) } }
            return
        }
        if (!destination.isDirectory && !destination.mkdirs()) throw IOException("Cannot create $destination")
        for (child in children) copyTree(assets, "$path/$child", File(destination, child), reusedPack)
    }
}
