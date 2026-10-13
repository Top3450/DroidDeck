package com.droiddeck.launcher.gpu

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.FileUtils
import org.json.JSONObject
import java.io.File

/**
 * Personal build (DroidDeck TH): a Linux-side Vulkan driver for phones with an Arm Mali GPU, where
 * the runtime's Turnip finds no device ("failed to find physical device") and the session ends.
 *
 * The driver is Mesa's PanVK built to talk straight to Android's mali_kbase (/dev/mali0) - the
 * same arrangement Turnip has with KGSL - from zenithblue-oss/panvk-kbase-android
 * (assets/linux_driver/NOTICE.txt). It is a glibc build, so it loads in the runtime's processes
 * like any imported "-Linux" driver: it is installed into [LinuxVulkanDriverManager]'s store under
 * [ID] and handed to the session as BL_VK_DRIVER when nothing else is chosen. The session script
 * recognises it by that id and adds the switches PanVK needs (droiddeck-session).
 *
 * Experimental: its authors validated it on a Mali-G615 MC6 only.
 */
object MaliDriver {
    private const val TAG = "MaliDriver"

    /** The store id; the session script keys PanVK's switches on "panvk" in the path. */
    const val ID = "panvk-kbase-g615"
    /** Bumped whenever the bundled file changes, so the installed copy is replaced. */
    const val VERSION = "g615-v11-csf-v0.1.0-beta.11+nodrm"
    private const val ASSET = "linux_driver/libvulkan_panvk_kbase.so"
    private const val STAMP = "bundled-version"

    /** A Mali phone: no KGSL, and kbase's device node or the EGL vendor says Mali. */
    @JvmStatic
    fun isMali(): Boolean {
        if (GpuInfo.detect().family != GpuInfo.Family.NOT_ADRENO) return false
        if (File("/dev/mali0").exists()) return true
        return GpuInfo.systemProperty("ro.hardware.egl").contains("mali", ignoreCase = true) ||
            GpuInfo.systemProperty("ro.hardware.vulkan").contains("mali", ignoreCase = true)
    }

    /**
     * The bundled driver's ICD manifest, installed (or refreshed after an app update) on first use;
     * null on a GPU that is not Mali, or when the install fails - the session then keeps the
     * runtime's own driver, as before.
     */
    @JvmStatic
    @Synchronized
    fun icdPath(context: Context): String? {
        if (!isMali()) return null
        val store = LinuxVulkanDriverManager(context)
        val dir = store.getDriverDir(ID)
        val stamp = File(dir, STAMP)
        if (store.isInstalled(ID) && FileUtils.readString(stamp)?.trim() == VERSION) return store.getIcdPath(ID)
        Log.i(TAG, "installing bundled PanVK kbase $VERSION into $dir")
        FileUtils.delete(dir)
        dir.mkdirs()
        // Written under the store's library name, so the store treats it like any imported driver;
        // the loader opens it by the manifest's absolute path, so the file name does not matter.
        val library = File(dir, LinuxVulkanDriverManager.LIB_NAME)
        val ok = FileUtils.copyAsset(context, ASSET, library) &&
            FileUtils.writeString(File(dir, LinuxVulkanDriverManager.ICD_NAME), JSONObject()
                .put("file_format_version", "1.0.0")
                .put("ICD", JSONObject()
                    .put("library_path", library.absolutePath)
                    .put("api_version", "1.4.363"))
                .toString(2)) &&
            FileUtils.writeString(File(dir, "meta.json"), JSONObject()
                .put("name", "PanVK kbase (Mali)")
                .put("driverVersion", VERSION.removePrefix("g615-v11-csf-"))
                .put("minGlibc", "2.38")
                .toString(2)) &&
            FileUtils.writeString(stamp, VERSION)
        if (!ok || !store.isInstalled(ID)) {
            Log.e(TAG, "could not install the bundled PanVK driver; keeping the runtime's own")
            FileUtils.delete(dir)
            return null
        }
        return store.getIcdPath(ID)
    }
}
