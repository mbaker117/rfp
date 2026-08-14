package com.rfp.service.crawl

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

internal data class CatalogDocumentParserSettings(
    val maxDocumentBytes: Int,
    val maxPages: Int,
    val maxSheets: Int,
    val maxSections: Int,
    val maxExpandedBytes: Long,
    val maxArchiveEntryBytes: Long,
    val maxArchiveEntries: Int,
    val maxTextCharacters: Long,
    val maxRows: Long,
    val maxCells: Long,
    val maxParagraphs: Long,
    val maxTableRows: Long,
    val maxXmlDepth: Int,
    val maxXmlElements: Long,
    val maxRuns: Long,
) {
    init {
        require(maxDocumentBytes > 0)
        require(maxPages > 0)
        require(maxSheets > 0)
        require(maxSections > 0)
        require(maxExpandedBytes > 0)
        require(maxArchiveEntryBytes > 0)
        require(maxArchiveEntries > 0)
        require(maxTextCharacters > 0)
        require(maxRows > 0)
        require(maxCells > 0)
        require(maxParagraphs > 0)
        require(maxTableRows > 0)
        require(maxXmlDepth > 0)
        require(maxXmlElements > 0)
        require(maxRuns > 0)
    }
}

internal data class CatalogDocumentWorkerRequest(
    val settings: CatalogDocumentParserSettings,
    val maximumDuration: Duration,
    val maxWorkerOutputBytes: Long,
    val bytes: ByteArray,
    val contentType: String,
    val sourceUrl: URI,
)

internal class CatalogDocumentWorkerClient(
    private val settings: CatalogDocumentParserSettings,
    private val maximumDuration: Duration,
    private val workerMaxHeapMegabytes: Int,
    private val maxWorkerOutputBytes: Long,
) {
    init {
        require(!maximumDuration.isNegative && !maximumDuration.isZero)
        require(workerMaxHeapMegabytes in 16..4_096)
        require(maxWorkerOutputBytes in 64..256L * 1024 * 1024)
    }

    fun parse(bytes: ByteArray, contentType: String, sourceUrl: URI): ParsedDocument {
        if (bytes.size > settings.maxDocumentBytes) reject(DocumentRejectionReason.OVERSIZED)
        val started = System.nanoTime()
        val temporaryDirectory = Files.createTempDirectory("catalog-document-worker-")
        val requestPath = temporaryDirectory.resolve("request.bin")
        val responsePath = temporaryDirectory.resolve("response.bin")
        var process: Process? = null
        try {
            CatalogDocumentWorkerProtocol.writeRequest(
                requestPath,
                CatalogDocumentWorkerRequest(
                    settings,
                    maximumDuration,
                    maxWorkerOutputBytes,
                    bytes,
                    contentType,
                    sourceUrl,
                ),
            )
            process = startWorker(requestPath, responsePath)
            val remainingNanos = remainingNanos(started)
            if (remainingNanos == 0L || !process.waitFor(remainingNanos, TimeUnit.NANOSECONDS)) {
                terminateExactly(process)
                reject(DocumentRejectionReason.TIME_LIMIT_EXCEEDED)
            }
            if (!responsePath.exists()) reject(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED)
            if (Files.size(responsePath) > maxWorkerOutputBytes) reject(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED)
            return CatalogDocumentWorkerProtocol.readResponse(
                responsePath,
                sourceUrl,
                settings.maxSections,
                maxWorkerOutputBytes,
            )
        } catch (rejection: CatalogDocumentRejectedException) {
            throw rejection
        } catch (interrupted: InterruptedException) {
            process?.let(::terminateExactly)
            Thread.currentThread().interrupt()
            reject(DocumentRejectionReason.TIME_LIMIT_EXCEEDED, interrupted)
        } catch (exception: Exception) {
            reject(DocumentRejectionReason.MALFORMED, exception)
        } finally {
            if (process?.isAlive == true) terminateExactly(process)
            Files.deleteIfExists(responsePath)
            Files.deleteIfExists(requestPath)
            Files.deleteIfExists(temporaryDirectory)
        }
    }

    private fun remainingNanos(started: Long): Long {
        val allowed = runCatching { maximumDuration.toNanos() }.getOrElse { Long.MAX_VALUE }
        return (allowed - (System.nanoTime() - started)).coerceAtLeast(0)
    }

    private fun startWorker(requestPath: Path, responsePath: Path): Process {
        val launch = CatalogDocumentWorkerLaunch.resolve()
        val command = mutableListOf(
            javaExecutable(),
            "-Xmx${workerMaxHeapMegabytes}m",
            "-Djava.awt.headless=true",
        )
        command += launch.arguments
        command += requestPath.absolutePathString()
        command += responsePath.absolutePathString()
        return ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment()["CLASSPATH"] = launch.classpath }
            .start()
            .also { it.outputStream.close() }
    }

    private fun javaExecutable(): String {
        val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", executable).absolutePathString()
    }

    private fun terminateExactly(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }
}

private data class WorkerLaunch(val classpath: String, val arguments: List<String>)

private object CatalogDocumentWorkerLaunch {
    private const val WORKER_MAIN = "com.rfp.service.crawl.CatalogDocumentWorkerMain"

    fun resolve(): WorkerLaunch {
        val entries = runtimeClasspathEntries()
        val bootJar = entries.firstOrNull(::isSpringBootJar)
        return if (bootJar != null) {
            WorkerLaunch(
                classpath = bootJar,
                arguments = listOf(
                    "-Dloader.main=$WORKER_MAIN",
                    "org.springframework.boot.loader.launch.PropertiesLauncher",
                ),
            )
        } else {
            WorkerLaunch(entries.joinToString(System.getProperty("path.separator")), listOf(WORKER_MAIN))
        }
    }

    private fun runtimeClasspathEntries(): List<String> {
        val entries = LinkedHashSet<String>()
        System.getProperty("java.class.path")
            ?.split(System.getProperty("path.separator"))
            ?.filter(String::isNotBlank)
            ?.forEach(entries::add)
        var classLoader: ClassLoader? = Thread.currentThread().contextClassLoader
        while (classLoader != null) {
            if (classLoader is URLClassLoader) {
                classLoader.urLs.filter { it.protocol == "file" }.forEach { url ->
                    runCatching { Path.of(url.toURI()).absolutePathString() }.getOrNull()?.let(entries::add)
                }
            }
            classLoader = classLoader.parent
        }
        CatalogDocumentWorkerClient::class.java.protectionDomain.codeSource?.location?.let { location ->
            if (location.protocol == "file") {
                runCatching { Path.of(location.toURI()).absolutePathString() }.getOrNull()?.let(entries::add)
            }
        }
        return entries.filter { Path.of(it).exists() }
    }

    private fun isSpringBootJar(entry: String): Boolean {
        val path = runCatching { Path.of(entry) }.getOrNull() ?: return false
        if (!Files.isRegularFile(path) || !entry.endsWith(".jar", ignoreCase = true)) return false
        return runCatching {
            JarFile(path.toFile()).use { jar ->
                jar.getEntry("BOOT-INF/classes/com/rfp/service/crawl/CatalogDocumentWorkerMain.class") != null
            }
        }.getOrDefault(false)
    }
}

internal object CatalogDocumentWorkerProtocol {
    private const val REQUEST_MAGIC = 0x43445051
    private const val RESPONSE_MAGIC = 0x43445052
    private const val VERSION = 1
    private const val SUCCESS = 0
    private const val REJECTED = 1

    fun writeRequest(path: Path, request: CatalogDocumentWorkerRequest) {
        DataOutputStream(Files.newOutputStream(path)).use { output ->
            output.writeInt(REQUEST_MAGIC)
            output.writeInt(VERSION)
            request.settings.writeTo(output)
            output.writeLong(runCatching { request.maximumDuration.toNanos() }.getOrElse { Long.MAX_VALUE })
            output.writeLong(request.maxWorkerOutputBytes)
            output.writeString(request.contentType)
            output.writeString(request.sourceUrl.toString())
            output.writeInt(request.bytes.size)
            output.write(request.bytes)
        }
    }

    fun readRequest(path: Path): CatalogDocumentWorkerRequest = DataInputStream(Files.newInputStream(path)).use { input ->
        check(input.readInt() == REQUEST_MAGIC) { "invalid worker request" }
        check(input.readInt() == VERSION) { "unsupported worker request version" }
        val settings = input.readSettings()
        val maximumDuration = Duration.ofNanos(input.readLong()).also {
            require(!it.isNegative && !it.isZero)
        }
        val maxWorkerOutputBytes = input.readLong().also { require(it in 64..256L * 1024 * 1024) }
        val contentType = input.readString(64 * 1024L)
        val sourceUrl = URI(input.readString(1024 * 1024L))
        val length = input.readInt()
        require(length in 0..settings.maxDocumentBytes)
        val bytes = ByteArray(length)
        input.readFully(bytes)
        CatalogDocumentWorkerRequest(settings, maximumDuration, maxWorkerOutputBytes, bytes, contentType, sourceUrl)
    }

    fun writeSuccess(path: Path, parsed: ParsedDocument, maximumBytes: Long) {
        boundedDataOutput(path, maximumBytes).use { output ->
            output.writeInt(RESPONSE_MAGIC)
            output.writeInt(VERSION)
            output.writeByte(SUCCESS)
            output.writeString(parsed.contentType)
            output.writeInt(parsed.fragments.size)
            parsed.fragments.forEach { fragment ->
                output.writeString(fragment.text)
                output.writeInt(fragment.provenance.page ?: -1)
                output.writeNullableString(fragment.provenance.sheet)
                output.writeNullableString(fragment.provenance.section)
            }
        }
    }

    fun writeRejection(path: Path, reason: DocumentRejectionReason, maximumBytes: Long = 1024) {
        boundedDataOutput(path, maximumBytes).use { output ->
            output.writeInt(RESPONSE_MAGIC)
            output.writeInt(VERSION)
            output.writeByte(REJECTED)
            output.writeInt(reason.ordinal)
        }
    }

    fun readResponse(path: Path, sourceUrl: URI, maxSections: Int, maximumBytes: Long): ParsedDocument =
        DataInputStream(Files.newInputStream(path)).use { input ->
            check(input.readInt() == RESPONSE_MAGIC) { "invalid worker response" }
            check(input.readInt() == VERSION) { "unsupported worker response version" }
            when (input.readUnsignedByte()) {
                REJECTED -> {
                    val ordinal = input.readInt()
                    val reason = DocumentRejectionReason.entries.getOrNull(ordinal)
                        ?: throw IOException("invalid worker rejection")
                    reject(reason)
                }
                SUCCESS -> {
                    val contentType = input.readString(maximumBytes)
                    val count = input.readInt()
                    require(count in 0..maxSections)
                    val fragments = List(count) {
                        val text = input.readString(maximumBytes)
                        val page = input.readInt().takeIf { it >= 0 }
                        val sheet = input.readNullableString(maximumBytes)
                        val section = input.readNullableString(maximumBytes)
                        DocumentFragment(text, DocumentProvenance(sourceUrl, page, sheet, section))
                    }
                    ParsedDocument(sourceUrl, contentType, fragments)
                }
                else -> throw IOException("invalid worker response status")
            }
        }

    private fun CatalogDocumentParserSettings.writeTo(output: DataOutputStream) {
        output.writeInt(maxDocumentBytes)
        output.writeInt(maxPages)
        output.writeInt(maxSheets)
        output.writeInt(maxSections)
        output.writeLong(maxExpandedBytes)
        output.writeLong(maxArchiveEntryBytes)
        output.writeInt(maxArchiveEntries)
        output.writeLong(maxTextCharacters)
        output.writeLong(maxRows)
        output.writeLong(maxCells)
        output.writeLong(maxParagraphs)
        output.writeLong(maxTableRows)
        output.writeInt(maxXmlDepth)
        output.writeLong(maxXmlElements)
        output.writeLong(maxRuns)
    }

    private fun DataInputStream.readSettings() = CatalogDocumentParserSettings(
        maxDocumentBytes = readInt(),
        maxPages = readInt(),
        maxSheets = readInt(),
        maxSections = readInt(),
        maxExpandedBytes = readLong(),
        maxArchiveEntryBytes = readLong(),
        maxArchiveEntries = readInt(),
        maxTextCharacters = readLong(),
        maxRows = readLong(),
        maxCells = readLong(),
        maxParagraphs = readLong(),
        maxTableRows = readLong(),
        maxXmlDepth = readInt(),
        maxXmlElements = readLong(),
        maxRuns = readLong(),
    )

    private fun boundedDataOutput(path: Path, maximumBytes: Long): DataOutputStream =
        DataOutputStream(BoundedOutputStream(Files.newOutputStream(path), maximumBytes))

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) writeInt(-1) else writeString(value)
    }

    private fun DataInputStream.readString(maximumBytes: Long): String {
        val length = readInt()
        require(length >= 0 && length.toLong() <= maximumBytes)
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun DataInputStream.readNullableString(maximumBytes: Long): String? {
        val length = readInt()
        if (length == -1) return null
        require(length >= 0 && length.toLong() <= maximumBytes)
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}

private class BoundedOutputStream(
    private val delegate: OutputStream,
    private val maximumBytes: Long,
) : OutputStream() {
    private var written = 0L

    override fun write(value: Int) {
        reserve(1)
        delegate.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        reserve(length)
        delegate.write(buffer, offset, length)
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()

    private fun reserve(count: Int) {
        written = try {
            Math.addExact(written, count.toLong())
        } catch (_: ArithmeticException) {
            throw WorkerOutputLimitException()
        }
        if (written > maximumBytes) throw WorkerOutputLimitException()
    }
}

private class WorkerOutputLimitException : IOException("worker output limit exceeded")

object CatalogDocumentWorkerMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.size != 2) return
        val requestPath = Path.of(arguments[0])
        val responsePath = Path.of(arguments[1])
        var responseLimit = 1024L
        try {
            val request = CatalogDocumentWorkerProtocol.readRequest(requestPath)
            responseLimit = request.maxWorkerOutputBytes
            val parsed = CatalogDocumentParserCore(request.settings).parse(
                request.bytes,
                request.contentType,
                request.sourceUrl,
                request.maximumDuration,
            )
            CatalogDocumentWorkerProtocol.writeSuccess(responsePath, parsed, responseLimit)
        } catch (rejection: CatalogDocumentRejectedException) {
            writeFallbackRejection(responsePath, rejection.reason, responseLimit)
        } catch (_: OutOfMemoryError) {
            writeFallbackRejection(responsePath, DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED, responseLimit)
        } catch (_: StackOverflowError) {
            writeFallbackRejection(responsePath, DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED, responseLimit)
        } catch (_: WorkerOutputLimitException) {
            writeFallbackRejection(responsePath, DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED, responseLimit)
        } catch (_: Throwable) {
            writeFallbackRejection(responsePath, DocumentRejectionReason.MALFORMED, responseLimit)
        }
    }

    private fun writeFallbackRejection(path: Path, reason: DocumentRejectionReason, maximumBytes: Long) {
        runCatching {
            Files.deleteIfExists(path)
            CatalogDocumentWorkerProtocol.writeRejection(path, reason, maximumBytes.coerceAtLeast(64))
        }
    }
}

private fun reject(reason: DocumentRejectionReason, cause: Throwable? = null): Nothing =
    throw CatalogDocumentRejectedException(reason, cause)
