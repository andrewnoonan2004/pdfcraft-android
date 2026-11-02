package com.andrewnoonan.pdfcraft.storage

object SafMimeTypeMapper {

    fun openMimeTypes(extensions: List<String>): Array<String> {
        if (extensions.isEmpty()) {
            return arrayOf("*/*")
        }
        val mapped = LinkedHashSet<String>()
        for (rawExt in extensions) {
            val mime = extensionToMimeType(rawExt) ?: return arrayOf("*/*")
            mapped += mime
        }
        return if (mapped.isEmpty()) arrayOf("*/*") else mapped.toTypedArray()
    }

    fun createMimeType(suggestedName: String?, extensions: List<String>): String {
        val fromName = suggestedName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() }
            ?.let(::extensionToMimeType)
        if (fromName != null) {
            return fromName
        }
        for (ext in extensions) {
            val mime = extensionToMimeType(ext)
            if (mime != null) {
                return mime
            }
        }
        return "application/pdf"
    }

    fun extensionToMimeType(rawExtension: String): String? {
        return when (rawExtension.trim().trimStart('.').lowercase()) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "tif", "tiff" -> "image/tiff"
            "txt", "md", "log" -> "text/plain"
            "csv" -> "text/csv"
            "tsv" -> "text/tab-separated-values"
            "html", "htm" -> "text/html"
            "rtf" -> "application/rtf"
            "json" -> "application/json"
            "xml", "xfdf", "fdf" -> "application/xml"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "p12", "pfx" -> "application/x-pkcs12"
            "pem", "cer", "crt" -> "application/x-x509-ca-cert"
            else -> null
        }
    }
}
