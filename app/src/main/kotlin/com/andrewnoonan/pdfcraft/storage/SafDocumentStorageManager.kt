package com.andrewnoonan.pdfcraft.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

data class ImportedDocument(
    val displayName: String,
    val localPath: String,
    val originUri: Uri?,
)

interface DocumentStorageManager {
    fun importReadableUri(uri: Uri, takePersistablePermission: Boolean = true): ImportedDocument?
    fun prepareSaveDestinationUri(uri: Uri, suggestedName: String): String?
    fun prepareFolderDestinationUri(treeUri: Uri): String?
    fun syncSavedPathToOrigin(savedLocalPath: String): Boolean
    fun sanitizeFileName(rawName: String, fallback: String = "document.pdf"): String
}

@Serializable
private data class OriginBinding(
    val uriString: String,
    val isFolder: Boolean = false,
)

class SafDocumentStorageManager(
    private val context: Context,
    private val workspaceRoot: File = File(context.filesDir, "saf_workspace"),
) : DocumentStorageManager {

    private val json = Json { ignoreUnknownKeys = true }
    private val bindingsFile = File(workspaceRoot, "uri_bindings.json")
    private val bindings = LinkedHashMap<String, OriginBinding>()

    init {
        workspaceRoot.mkdirs()
        loadBindings()
    }

    @Synchronized
    override fun importReadableUri(uri: Uri, takePersistablePermission: Boolean): ImportedDocument? {
        if (takePersistablePermission && uri.scheme == "content") {
            tryTakePersistablePermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val displayName = resolveDisplayName(uri, fallback = "document.pdf")
        val bucket = File(File(workspaceRoot, "docs"), shortHash(uri.toString())).apply { mkdirs() }
        val stagedFile = File(bucket, displayName)
        val tmpFile = File(bucket, ".$displayName.import.tmp")

        val copied = openUriInputStream(uri)?.use { input ->
            writeBoundedStream(input, tmpFile)
        } ?: false
        if (!copied) {
            tmpFile.delete()
            return null
        }
        if (stagedFile.exists()) {
            stagedFile.delete()
        }
        if (!tmpFile.renameTo(stagedFile)) {
            runCatching {
                tmpFile.copyTo(stagedFile, overwrite = true)
                tmpFile.delete()
            }.onFailure {
                return null
            }
        }

        registerBinding(stagedFile, OriginBinding(uriString = uri.toString(), isFolder = false))
        return ImportedDocument(
            displayName = displayName,
            localPath = stagedFile.absolutePath,
            originUri = uri,
        )
    }

    @Synchronized
    override fun prepareSaveDestinationUri(uri: Uri, suggestedName: String): String? {
        if (uri.scheme == "content") {
            tryTakePersistablePermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val cleanFallback = sanitizeFileName(suggestedName, "document.pdf")
        val displayName = resolveDisplayName(uri, fallback = cleanFallback)
        val bucket = File(File(workspaceRoot, "saves"), shortHash(uri.toString())).apply { mkdirs() }
        val destFile = File(bucket, displayName)
        registerBinding(destFile, OriginBinding(uriString = uri.toString(), isFolder = false))
        return destFile.absolutePath
    }

    @Synchronized
    override fun prepareFolderDestinationUri(treeUri: Uri): String? {
        if (treeUri.scheme == "content") {
            tryTakePersistablePermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val folderDir = File(File(workspaceRoot, "trees"), shortHash(treeUri.toString())).apply { mkdirs() }
        registerBinding(folderDir, OriginBinding(uriString = treeUri.toString(), isFolder = true))
        return folderDir.absolutePath
    }

    @Synchronized
    override fun syncSavedPathToOrigin(savedLocalPath: String): Boolean {
        val localFile = File(savedLocalPath)
        if (!localFile.isFile) {
            return false
        }
        val directBinding = lookupBinding(localFile)
        if (directBinding != null && !directBinding.isFolder) {
            return writeLocalFileToUri(localFile, Uri.parse(directBinding.uriString))
        }

        val parentDir = localFile.parentFile
        val folderBinding = parentDir?.let { lookupBinding(it) }
        if (folderBinding != null && folderBinding.isFolder) {
            return syncFileIntoTreeUri(localFile, Uri.parse(folderBinding.uriString))
        }
        return false
    }

    override fun sanitizeFileName(rawName: String, fallback: String): String {
        val base = rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .buildSanitizedChars()
            .replace(Regex("_+"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', '_')
            .trim()

        if (base.isEmpty() || base == "." || base == "..") {
            return fallback
        }
        return if (base.length > 180) base.take(180) else base
    }

    private fun String.buildSanitizedChars(): String {
        val sb = StringBuilder(length)
        for (ch in this) {
            when {
                ch.code in 0..0x1F -> {}
                ch in "?*\"<>|" -> {}
                ch == ':' -> sb.append('_')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun resolveDisplayName(uri: Uri, fallback: String): String {
        if (uri.scheme == "content") {
            val queried = runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else {
                        null
                    }
                }
            }.getOrNull()
            if (!queried.isNullOrBlank()) {
                return sanitizeFileName(queried, fallback)
            }
        }
        val segment = uri.lastPathSegment
        return if (!segment.isNullOrBlank()) {
            sanitizeFileName(segment, fallback)
        } else {
            fallback
        }
    }

    private fun openUriInputStream(uri: Uri): InputStream? {
        val fromResolver = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        if (fromResolver != null) {
            return fromResolver
        }
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val file = File(path)
            if (file.isFile) {
                return runCatching { FileInputStream(file) }.getOrNull()
            }
        }
        return null
    }

    private fun writeBoundedStream(input: InputStream, target: File): Boolean {
        return runCatching {
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMPORT_BYTES) {
                        throw IllegalStateException("File exceeds maximum import size")
                    }
                    out.write(buffer, 0, read)
                }
                out.fd.sync()
            }
            true
        }.getOrDefault(false)
    }

    private fun writeLocalFileToUri(localFile: File, originUri: Uri): Boolean {
        if (originUri.scheme == "file") {
            val destPath = originUri.path ?: return false
            val destFile = File(destPath)
            if (destFile.canonicalPath == localFile.canonicalPath) {
                return true
            }
            return runCatching {
                localFile.copyTo(destFile, overwrite = true)
                true
            }.getOrDefault(false)
        }
        return runCatching {
            val out = context.contentResolver.openOutputStream(originUri, "wt")
                ?: context.contentResolver.openOutputStream(originUri, "w")
                ?: return false
            out.use { stream ->
                FileInputStream(localFile).use { input ->
                    input.copyTo(stream)
                }
                stream.flush()
            }
            true
        }.getOrDefault(false)
    }

    private fun syncFileIntoTreeUri(localFile: File, treeUri: Uri): Boolean {
        if (treeUri.scheme == "file") {
            val treeDir = File(treeUri.path ?: return false)
            treeDir.mkdirs()
            return runCatching {
                localFile.copyTo(File(treeDir, localFile.name), overwrite = true)
                true
            }.getOrDefault(false)
        }
        return runCatching {
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val existing = tree.findFile(localFile.name)
            val targetDoc = existing ?: tree.createFile(guessMimeType(localFile.name), localFile.name) ?: return false
            writeLocalFileToUri(localFile, targetDoc.uri)
        }.getOrDefault(false)
    }

    private fun guessMimeType(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "rtf" -> "application/rtf"
            "csv" -> "text/csv"
            "xfdf" -> "application/vnd.adobe.xfdf"
            else -> "application/octet-stream"
        }
    }

    private fun tryTakePersistablePermission(uri: Uri, flags: Int) {
        val resolver = context.contentResolver
        val bothSucceeded = runCatching {
            resolver.takePersistableUriPermission(uri, flags)
            true
        }.getOrDefault(false)
        if (!bothSucceeded) {
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun registerBinding(file: File, binding: OriginBinding) {
        bindings[file.absolutePath] = binding
        runCatching { file.canonicalPath }.getOrNull()?.let { canonical ->
            bindings[canonical] = binding
        }
        persistBindings()
    }

    private fun lookupBinding(file: File): OriginBinding? {
        bindings[file.absolutePath]?.let { return it }
        val canonical = runCatching { file.canonicalPath }.getOrNull()
        return canonical?.let { bindings[it] }
    }

    private fun loadBindings() {
        if (!bindingsFile.isFile) return
        runCatching {
            val decoded = json.decodeFromString<Map<String, OriginBinding>>(bindingsFile.readText())
            bindings.putAll(decoded)
        }
    }

    private fun persistBindings() {
        runCatching {
            val tmp = File(workspaceRoot, "uri_bindings.json.tmp")
            tmp.writeText(json.encodeToString<Map<String, OriginBinding>>(bindings))
            tmp.renameTo(bindingsFile)
        }
    }

    private fun shortHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_IMPORT_BYTES = 1024L * 1024L * 1024L // 1 GiB cap
    }
}
