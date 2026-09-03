package com.scriptoria.browser.engine.parser

object UserscriptParser {

    const val DEFAULT_NAME = "Unnamed Userscript"

    private val BLOCK_REGEX = Regex(
        """[ \t]*//\s*==UserScript==[ \t]*\r?\n([\s\S]*?)\r?\n[ \t]*//\s*==/UserScript==[ \t]*""",
        RegexOption.MULTILINE
    )

    private val LINE_REGEX = Regex(
        """//\s*@([\w-]+)(?::([\w-]+))?(?:\s+(.*))?"""
    )

    fun parse(code: String, fallbackUrl: String? = null): UserscriptMetadata {
        val cleanCode = code.trimStart('\uFEFF')
        val blockMatch = BLOCK_REGEX.find(cleanCode)
        val block = blockMatch?.groupValues?.get(1).orEmpty()

        val multiTags = mutableMapOf<String, MutableList<String>>()
        val resources = mutableMapOf<String, String>()

        block.lineSequence().forEach { line ->
            val match = LINE_REGEX.find(line.trim()) ?: return@forEach
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[3].trim()

            if (key == "resource") {
                val spaceIdx = value.indexOfFirst { it.isWhitespace() }
                if (spaceIdx > 0) {
                    val resName = value.substring(0, spaceIdx).trim()
                    val resUrl = value.substring(spaceIdx).trim()
                    if (resName.isNotEmpty() && resUrl.isNotEmpty()) {
                        resources[resName] = resUrl
                    }
                }
            } else if (value.isNotEmpty() || key == "noframes") {
                multiTags.getOrPut(key) { mutableListOf() }.add(value)
            }
        }

        fun first(vararg keys: String): String =
            keys.firstNotNullOfOrNull { multiTags[it]?.firstOrNull() }.orEmpty()

        fun all(vararg keys: String): List<String> =
            keys.flatMap { multiTags[it].orEmpty() }

        val fallbackName = fallbackUrl?.let { url ->
            val clean = url.substringBefore('?').substringBefore('#')
            val filePart = clean.substringAfterLast('/')
            if (filePart.endsWith(".user.js")) filePart.removeSuffix(".user.js") else filePart
        }?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME

        val rawName = first("name")
        val name = if (rawName.isBlank()) fallbackName else rawName
        val runAt = RunAt.fromString(first("run-at"))

        return UserscriptMetadata(
            name = name,
            namespace = first("namespace"),
            version = first("version"),
            description = first("description"),
            author = first("author"),
            matches = all("match"),
            includes = all("include"),
            excludes = all("exclude", "exclude-match"),
            grants = all("grant"),
            requires = all("require"),
            resources = resources,
            connects = all("connect"),
            runAt = runAt,
            noFrames = multiTags.containsKey("noframes"),
            updateUrl = first("updateurl").ifEmpty { null },
            downloadUrl = first("downloadurl").ifEmpty { null },
            installUrl = first("installurl").ifEmpty { fallbackUrl }
        )
    }

    /**
     * Compares two version strings segment-by-segment on `.`.
     * Returns:
     *  > 0 if [remote] > [current] (remote is strictly newer)
     *  = 0 if identical
     *  < 0 if [remote] < [current]
     */
    fun compareVersions(remote: String, current: String): Int {
        if (remote.isBlank() && current.isBlank()) return 0
        if (remote.isBlank()) return -1
        if (current.isBlank()) return 1

        val rParts = remote.trim().split('.')
        val cParts = current.trim().split('.')
        val maxLen = maxOf(rParts.size, cParts.size)

        for (i in 0 until maxLen) {
            val rSegment = rParts.getOrElse(i) { "0" }
            val cSegment = cParts.getOrElse(i) { "0" }

            val rNum = rSegment.takeWhile { it.isDigit() }.toLongOrNull() ?: 0L
            val cNum = cSegment.takeWhile { it.isDigit() }.toLongOrNull() ?: 0L

            if (rNum != cNum) {
                return rNum.compareTo(cNum)
            }

            val rSuffix = rSegment.dropWhile { it.isDigit() }
            val cSuffix = cSegment.dropWhile { it.isDigit() }

            if (rSuffix != cSuffix) {
                // A plain release (empty suffix) is newer than a pre-release suffix (e.g. -beta)
                if (rSuffix.isEmpty()) return 1
                if (cSuffix.isEmpty()) return -1
                return rSuffix.compareTo(cSuffix)
            }
        }
        return 0
    }

    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        return compareVersions(remoteVersion, currentVersion) > 0
    }
}
