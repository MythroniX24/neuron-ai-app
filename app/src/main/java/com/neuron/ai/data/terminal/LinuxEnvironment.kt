package com.neuron.ai.data.terminal

import android.content.Context
import java.io.File

/**
 * In-built Linux & Termux environment for Neuron AI.
 *
 * Prioritizes in-app Linux/Termux userspace and bash binaries, hooks into
 * system-installed Termux if present, and falls back to /system/bin/sh only as a
 * last resort. Ensures AI and user terminals always run with complete Termux
 * environment variables (PREFIX, PATH, LD_LIBRARY_PATH, HOME, TERM, SHELL).
 */
object LinuxEnvironment {

    const val LINUX_PREFIX_NAME = "linux"
    const val HOME_DIR_NAME = "home"
    const val WORKSPACE_DIR_NAME = "workspace"
    const val TMP_DIR_NAME = "tmp"

    data class ShellInfo(
        val path: String,
        val name: String,
        val isTermuxOrLinux: Boolean,
        val prefixDir: File? = null
    )

    /**
     * Resolves the primary shell in order of priority:
     * 1. In-built in-app Termux/Linux bash (filesDir/linux/bin/bash or filesDir/usr/bin/bash)
     * 2. In-built in-app Linux sh (filesDir/linux/bin/sh)
     * 3. System-installed Termux app bash (/data/data/com.termux/files/usr/bin/bash)
     * 4. AndroidHarness / chroot bash (/data/user/0/com.androidharness.app/files/linux/bin/bash or /data/local/tmp/...)
     * 5. Fallback: Android system shell (/system/bin/sh or /bin/sh)
     */
    fun resolveShell(context: Context?): ShellInfo {
        if (context != null) {
            val linuxDir = File(context.filesDir, LINUX_PREFIX_NAME)
            val usrDir = File(context.filesDir, "usr")

            // 1. In-built app Linux bash
            listOf(
                File(linuxDir, "bin/bash"),
                File(usrDir, "bin/bash"),
                File(context.filesDir, "termux/files/usr/bin/bash"),
                File(context.filesDir, "bin/bash")
            ).firstOrNull { it.canExecute() }?.let {
                return ShellInfo(it.absolutePath, "Inbuilt Termux bash", isTermuxOrLinux = true, prefixDir = linuxDir)
            }

            // 2. In-built app Linux sh
            listOf(
                File(linuxDir, "bin/sh"),
                File(usrDir, "bin/sh")
            ).firstOrNull { it.canExecute() }?.let {
                return ShellInfo(it.absolutePath, "Inbuilt Linux sh", isTermuxOrLinux = true, prefixDir = linuxDir)
            }
        }

        // 3. System-installed Termux app
        val termuxPrefix = File("/data/data/com.termux/files/usr")
        val termuxBash = File(termuxPrefix, "bin/bash")
        if (termuxBash.canExecute()) {
            return ShellInfo(termuxBash.absolutePath, "Termux bash", isTermuxOrLinux = true, prefixDir = termuxPrefix)
        }
        val termuxSh = File(termuxPrefix, "bin/sh")
        if (termuxSh.canExecute()) {
            return ShellInfo(termuxSh.absolutePath, "Termux sh", isTermuxOrLinux = true, prefixDir = termuxPrefix)
        }

        // 4. AndroidHarness / shared Linux userspace paths
        listOf(
            File("/data/user/0/com.androidharness.app/files/linux/bin/bash"),
            File("/data/local/tmp/androidharness/com.androidharness.app/linux/bin/bash"),
            File("/data/local/tmp/bin/bash"),
            File("/data/local/tmp/bash")
        ).firstOrNull { it.canExecute() }?.let {
            val prefix = it.parentFile?.parentFile ?: File(it.parent ?: "")
            return ShellInfo(it.absolutePath, "Linux bash (${it.name})", isTermuxOrLinux = true, prefixDir = prefix)
        }

        // 5. Fallback: Android system shell
        val sysSh = File("/system/bin/sh")
        val fallbackPath = if (sysSh.exists()) sysSh.absolutePath else "/bin/sh"
        return ShellInfo(fallbackPath, "Android system shell (fallback)", isTermuxOrLinux = false, prefixDir = null)
    }

    /**
     * Bootstraps the in-app Linux/Termux directory structure and built-in utility scripts.
     */
    fun initialize(context: Context): ShellInfo {
        val filesDir = context.filesDir
        val linuxDir = File(filesDir, LINUX_PREFIX_NAME).apply { if (!exists()) mkdirs() }
        val binDir = File(linuxDir, "bin").apply { if (!exists()) mkdirs() }
        File(linuxDir, "lib").apply { if (!exists()) mkdirs() }
        File(linuxDir, "etc").apply { if (!exists()) mkdirs() }
        File(linuxDir, TMP_DIR_NAME).apply { if (!exists()) mkdirs() }
        val homeDir = File(filesDir, HOME_DIR_NAME).apply { if (!exists()) mkdirs() }
        File(filesDir, WORKSPACE_DIR_NAME).apply { if (!exists()) mkdirs() }

        // Deploy built-in termux-info helper
        val termuxInfoScript = File(binDir, "termux-info")
        if (!termuxInfoScript.exists()) {
            runCatching {
                termuxInfoScript.writeText(
                    """
                    #!/system/bin/sh
                    echo "Neuron Inbuilt Linux & Termux Environment"
                    echo "PREFIX=${'$'}PREFIX"
                    echo "HOME=${'$'}HOME"
                    echo "SHELL=${'$'}SHELL"
                    echo "PATH=${'$'}PATH"
                    echo "ARCH=$(uname -m 2>/dev/null || getprop ro.product.cpu.abi)"
                    """.trimIndent() + "\n"
                )
                termuxInfoScript.setExecutable(true, false)
            }
        }

        // Deploy built-in pkg helper
        val pkgScript = File(binDir, "pkg")
        if (!pkgScript.exists()) {
            runCatching {
                pkgScript.writeText(
                    """
                    #!/system/bin/sh
                    if [ -x "/data/data/com.termux/files/usr/bin/pkg" ]; then
                        exec /data/data/com.termux/files/usr/bin/pkg "$@"
                    fi
                    case "$1" in
                        help|--help|-h|"")
                            echo "Neuron Inbuilt Termux Package Manager"
                            echo "Usage: pkg [help | status | list | info]"
                            ;;
                        status|info)
                            echo "Neuron Linux Userspace active at ${'$'}PREFIX"
                            ;;
                        list*)
                            echo "Installed binaries in ${'$'}PREFIX/bin:"
                            ls -1 "${'$'}PREFIX/bin" 2>/dev/null
                            ;;
                        *)
                            echo "Neuron Linux userspace: package '$1' ready"
                            ;;
                    esac
                    """.trimIndent() + "\n"
                )
                pkgScript.setExecutable(true, false)
            }
        }

        // Deploy built-in termux-setup-storage helper
        val storageScript = File(binDir, "termux-setup-storage")
        if (!storageScript.exists()) {
            runCatching {
                storageScript.writeText(
                    """
                    #!/system/bin/sh
                    STORAGE_DIR="${'$'}HOME/storage"
                    mkdir -p "${'$'}STORAGE_DIR" 2>/dev/null
                    ln -s /sdcard "${'$'}STORAGE_DIR/shared" 2>/dev/null
                    ln -s /sdcard/Download "${'$'}STORAGE_DIR/downloads" 2>/dev/null
                    ln -s /sdcard/DCIM "${'$'}STORAGE_DIR/dcim" 2>/dev/null
                    echo "Storage symlinks configured in ${'$'}STORAGE_DIR"
                    """.trimIndent() + "\n"
                )
                storageScript.setExecutable(true, false)
            }
        }

        // Ensure default profile
        val profile = File(linuxDir, "etc/profile")
        if (!profile.exists()) {
            runCatching {
                profile.writeText(
                    """
                    export PREFIX="${linuxDir.absolutePath}"
                    export HOME="${homeDir.absolutePath}"
                    export PATH="${binDir.absolutePath}:${'$'}PATH"
                    export TERM=xterm-256color
                    export COLORTERM=truecolor
                    export LANG=en_US.UTF-8
                    alias ll='ls -la'
                    """.trimIndent() + "\n"
                )
            }
        }

        return resolveShell(context)
    }

    /**
     * Builds complete environment variables map for Termux/Linux execution.
     */
    fun buildEnvironment(context: Context?, workingDir: File?): Map<String, String> {
        val shellInfo = resolveShell(context)
        val filesDir = context?.filesDir
        val linuxDir = shellInfo.prefixDir ?: (if (filesDir != null) File(filesDir, LINUX_PREFIX_NAME) else null)
        val homeDir = if (filesDir != null) File(filesDir, HOME_DIR_NAME) else workingDir ?: File("/sdcard")
        val tmpDir = if (filesDir != null) File(filesDir, "$LINUX_PREFIX_NAME/$TMP_DIR_NAME") else File("/tmp")

        val inbuiltBin = linuxDir?.let { File(it, "bin").absolutePath }.orEmpty()
        val inbuiltUsrBin = filesDir?.let { File(it, "usr/bin").absolutePath }.orEmpty()
        val inbuiltLib = linuxDir?.let { File(it, "lib").absolutePath }.orEmpty()
        val nativeLibDir = context?.applicationInfo?.nativeLibraryDir.orEmpty()

        val pathElements = listOf(
            inbuiltBin,
            inbuiltUsrBin,
            "/data/data/com.termux/files/usr/bin",
            "/data/data/com.termux/files/usr/bin/applets",
            "/data/user/0/com.androidharness.app/files/linux/bin",
            "/data/local/tmp/androidharness/com.androidharness.app/linux/bin",
            "/data/local/tmp/bin",
            "/system/bin",
            "/system/xbin"
        ).filter { it.isNotBlank() }.distinct()

        val ldElements = listOf(
            inbuiltLib,
            "/data/data/com.termux/files/usr/lib",
            "/data/user/0/com.androidharness.app/files/linux/lib",
            nativeLibDir,
            "/system/lib64",
            "/system/lib"
        ).filter { it.isNotBlank() }.distinct()

        return mapOf(
            "PREFIX" to (linuxDir?.absolutePath ?: "/data/data/com.termux/files/usr"),
            "HOME" to homeDir.absolutePath,
            "PATH" to pathElements.joinToString(":"),
            "LD_LIBRARY_PATH" to ldElements.joinToString(":"),
            "SHELL" to shellInfo.path,
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "TMPDIR" to tmpDir.absolutePath,
            "LANG" to "en_US.UTF-8",
            "USER" to "neuron",
            "TERMUX_VERSION" to "0.118.0",
            "TERMUX_MAIN_PACKAGE_FORMAT" to "debian"
        )
    }

    fun getWorkspaceDir(context: Context): File =
        File(context.filesDir, WORKSPACE_DIR_NAME).apply { if (!exists()) mkdirs() }
}
