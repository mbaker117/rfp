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
import java.util.Comparator
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import java.util.jar.JarFile
import kotlin.concurrent.withLock
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
    val sourceName: String,
)

internal interface CatalogDocumentWorkerObserver {
    fun onTemporaryDirectoryCreated(path: Path) = Unit
    fun onRequestWritten(path: Path) = Unit
    fun onProcessStarted(handle: ProcessHandle) = Unit
    fun onProcessExited(handle: ProcessHandle) = Unit
    fun onCleanupFailure(path: Path, failure: Throwable) = Unit

    companion object {
        val NONE: CatalogDocumentWorkerObserver = object : CatalogDocumentWorkerObserver {}
    }
}

internal class CatalogDocumentWorkerAdmissionController(
    private val maxConcurrentProcesses: Int,
    private val maxAggregateHeapMegabytes: Int,
) {
    private val lock = ReentrantLock(true)
    private val changed = lock.newCondition()
    private var activeProcesses = 0
    private var reservedHeapMegabytes = 0

    init {
        require(maxConcurrentProcesses > 0)
        require(maxAggregateHeapMegabytes > 0)
    }

    fun acquire(workerHeapMegabytes: Int, timeout: Duration): Lease? {
        require(workerHeapMegabytes > 0)
        require(!timeout.isNegative)
        var remaining = runCatching { timeout.toNanos() }.getOrElse { Long.MAX_VALUE }
        lock.lockInterruptibly()
        try {
            if (workerHeapMegabytes > maxAggregateHeapMegabytes) return null
            while (activeProcesses >= maxConcurrentProcesses ||
                reservedHeapMegabytes > maxAggregateHeapMegabytes - workerHeapMegabytes
            ) {
                if (remaining <= 0) return null
                remaining = changed.awaitNanos(remaining)
            }
            activeProcesses++
            reservedHeapMegabytes += workerHeapMegabytes
            return Lease { release(workerHeapMegabytes) }
        } finally {
            lock.unlock()
        }
    }

    private fun release(workerHeapMegabytes: Int) = lock.withLock {
        activeProcesses--
        reservedHeapMegabytes -= workerHeapMegabytes
        changed.signalAll()
    }

    internal class Lease(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}

private object CatalogDocumentWorkerGlobalAdmission {
    private const val MAX_PROCESSES_PROPERTY = "rfp.catalog.worker.max-processes"
    private const val MAX_AGGREGATE_HEAP_PROPERTY = "rfp.catalog.worker.max-aggregate-heap-mb"
    private const val DEFAULT_WORKER_HEAP_MEGABYTES = 128
    private const val MEBIBYTE = 1024L * 1024

    val controller: CatalogDocumentWorkerAdmissionController by lazy {
        val memoryDerivedProcesses =
            (Runtime.getRuntime().maxMemory() / (DEFAULT_WORKER_HEAP_MEGABYTES * MEBIBYTE)).toInt().coerceIn(1, 2)
        val maxProcesses = positiveSystemProperty(MAX_PROCESSES_PROPERTY) ?: memoryDerivedProcesses
        val aggregateHeap = positiveSystemProperty(MAX_AGGREGATE_HEAP_PROPERTY)
            ?: Math.multiplyExact(maxProcesses, DEFAULT_WORKER_HEAP_MEGABYTES)
        CatalogDocumentWorkerAdmissionController(maxProcesses, aggregateHeap)
    }

    private fun positiveSystemProperty(name: String): Int? =
        System.getProperty(name)?.toIntOrNull()?.takeIf { it > 0 }
}

internal class CatalogDocumentWorkerArtifacts(
    private val directory: Path,
    private val requestPath: Path,
    private val responsePath: Path,
    private val deletePath: (Path) -> Unit = { path ->
        Files.deleteIfExists(path)
        Unit
    },
    private val observer: CatalogDocumentWorkerObserver = CatalogDocumentWorkerObserver.NONE,
) {
    fun cleanup() {
        deleteIndependently(responsePath)
        deleteIndependently(requestPath)
        val extraPaths = runCatching {
            if (!Files.exists(directory)) emptyList() else Files.walk(directory).use { paths ->
                paths.filter { it != directory && it != requestPath && it != responsePath }
                    .sorted(Comparator.reverseOrder())
                    .toList()
            }
        }.onFailure { reportCleanupFailure(directory, it) }.getOrDefault(emptyList())
        extraPaths.forEach(::deleteIndependently)
        deleteDirectoryWithRetry()
    }

    private fun deleteIndependently(path: Path) {
        runCatching { deletePath(path) }
            .onFailure { reportCleanupFailure(path, it) }
    }

    private fun deleteDirectoryWithRetry() {
        var lastFailure: Throwable? = null
        repeat(10) { attempt ->
            try {
                deletePath(directory)
                return
            } catch (failure: Throwable) {
                lastFailure = failure
                if (attempt < 9) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
            }
        }
        reportCleanupFailure(directory, checkNotNull(lastFailure))
    }

    private fun reportCleanupFailure(path: Path, failure: Throwable) {
        runCatching { observer.onCleanupFailure(path, failure) }
    }
}

internal class CatalogDocumentWorkerClient(
    private val settings: CatalogDocumentParserSettings,
    private val maximumDuration: Duration,
    private val workerMaxHeapMegabytes: Int,
    private val maxWorkerOutputBytes: Long,
    private val admissionTimeout: Duration = Duration.ofSeconds(2),
    private val admission: CatalogDocumentWorkerAdmissionController = CatalogDocumentWorkerGlobalAdmission.controller,
    private val processLauncher: CatalogDocumentWorkerProcessStarter = CatalogDocumentWorkerProcessLauncher(),
    private val observer: CatalogDocumentWorkerObserver = CatalogDocumentWorkerObserver.NONE,
) {
    init {
        require(!maximumDuration.isNegative && !maximumDuration.isZero)
        require(!admissionTimeout.isNegative && !admissionTimeout.isZero)
        require(workerMaxHeapMegabytes in 16..4_096)
        require(maxWorkerOutputBytes in 64..256L * 1024 * 1024)
    }

    fun parse(bytes: ByteArray, contentType: String, sourceUrl: URI): ParsedDocument {
        if (bytes.size > settings.maxDocumentBytes) reject(DocumentRejectionReason.OVERSIZED)
        val started = System.nanoTime()
        val lease = try {
            admission.acquire(workerMaxHeapMegabytes, minimumDuration(admissionTimeout, maximumDuration))
                ?: reject(DocumentRejectionReason.WORKER_INFRASTRUCTURE_FAILURE)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            reject(DocumentRejectionReason.WORKER_INFRASTRUCTURE_FAILURE, interrupted)
        }
        val temporaryDirectory = try {
            Files.createTempDirectory("catalog-document-worker-")
        } catch (exception: Exception) {
            lease.close()
            reject(DocumentRejectionReason.WORKER_INFRASTRUCTURE_FAILURE, exception)
        }
        safelyNotify { observer.onTemporaryDirectoryCreated(temporaryDirectory) }
        val requestPath = temporaryDirectory.resolve("request.bin")
        val responsePath = temporaryDirectory.resolve("response.bin")
        val artifacts = CatalogDocumentWorkerArtifacts(
            temporaryDirectory,
            requestPath,
            responsePath,
            observer = observer,
        )
        var process: Process? = null
        var processExitObserved = false
        try {
            CatalogDocumentWorkerProtocol.writeRequest(
                requestPath,
                CatalogDocumentWorkerRequest(
                    settings,
                    maximumDuration,
                    maxWorkerOutputBytes,
                    bytes,
                    contentType,
                    sanitizedSourceName(sourceUrl),
                ),
            )
            safelyNotify { observer.onRequestWritten(requestPath) }
            process = processLauncher.start(
                temporaryDirectory,
                requestPath,
                responsePath,
                workerMaxHeapMegabytes,
            )
            safelyNotify { observer.onProcessStarted(process.toHandle()) }
            val remainingNanos = remainingNanos(started)
            if (remainingNanos == 0L || !process.waitFor(remainingNanos, TimeUnit.NANOSECONDS)) {
                if (!terminateExactly(process)) {
                    reject(DocumentRejectionReason.WORKER_INFRASTRUCTURE_FAILURE)
                }
                reject(DocumentRejectionReason.TIME_LIMIT_EXCEEDED)
            }
            safelyNotify { observer.onProcessExited(process.toHandle()) }
            processExitObserved = true
            if (!responsePath.exists()) {
                if (process.exitValue() == CatalogDocumentWorkerExit.RESOURCE_EXHAUSTED) {
                    reject(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED)
                }
                reject(DocumentRejectionReason.WORKER_INFRASTRUCTURE_FAILURE)
            }
            if (Files.size(responsePath) > maxWorkerOutputBytes) {
                reject(DocumentRejectionReason.WORKER_INFRASTRUCTURE_FAILURE)
            }
            return CatalogDocumentWorkerProtocol.readResponse(
                responsePath,
                sourceUrl,
                settings.maxSections,
                maxWorkerOutputBytes,
            )
        } catch (rejection: CatalogDocumentRejectedException) {
            throw rejection
        } catch (interrupted: InterruptedException) {
            val terminated = process?.let(::terminateExactly) ?: true
            Thread.currentThread().interrupt()
            if (!terminated) reject(DocumentRejectionReason.WORKER_INFRASTRUCTURE_FAILURE, interrupted)
            reject(DocumentRejectionReason.TIME_LIMIT_EXCEEDED, interrupted)
        } catch (exception: Exception) {
            reject(DocumentRejectionReason.WORKER_INFRASTRUCTURE_FAILURE, exception)
        } finally {
            process?.let { worker ->
                val terminated = runCatching { !worker.isAlive || terminateExactly(worker) }.getOrDefault(false)
                if (!terminated) {
                    safelyNotify {
                        observer.onCleanupFailure(
                            temporaryDirectory,
                            IOException("worker process ${worker.toHandle().pid()} remained alive after forced termination"),
                        )
                    }
                }
                if (!processExitObserved && terminated) safelyNotify { observer.onProcessExited(worker.toHandle()) }
            }
            artifacts.cleanup()
            lease.close()
        }
    }

    private fun remainingNanos(started: Long): Long {
        val allowed = runCatching { maximumDuration.toNanos() }.getOrElse { Long.MAX_VALUE }
        return (allowed - (System.nanoTime() - started)).coerceAtLeast(0)
    }

    private fun terminateExactly(process: Process): Boolean {
        if (!process.isAlive) return !process.toHandle().isAlive
        process.destroy()
        if (!waitWithoutMaskingInterrupt(process, 200, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            if (!waitWithoutMaskingInterrupt(process, 2, TimeUnit.SECONDS)) return false
        }
        return !process.isAlive && !process.toHandle().isAlive
    }

    private fun waitWithoutMaskingInterrupt(process: Process, timeout: Long, unit: TimeUnit): Boolean {
        val allowedNanos = unit.toNanos(timeout)
        val started = System.nanoTime()
        var remainingNanos = allowedNanos
        var interrupted = Thread.interrupted()
        try {
            while (true) {
                try {
                    return process.waitFor(remainingNanos, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    remainingNanos = (allowedNanos - (System.nanoTime() - started)).coerceAtLeast(0)
                    if (remainingNanos == 0L) return false
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun sanitizedSourceName(sourceUrl: URI): String {
        val filename = sourceUrl.path.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val extension = filename.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9]{1,16}")) }
        return if (extension == null) "document" else "document.$extension"
    }

    private fun minimumDuration(first: Duration, second: Duration): Duration =
        if (first <= second) first else second

    private inline fun safelyNotify(notification: () -> Unit) {
        runCatching(notification)
    }
}

internal object CatalogDocumentWorkerExit {
    const val RESOURCE_EXHAUSTED = 70
    const val INFRASTRUCTURE_FAILURE = 71
}

internal fun interface CatalogDocumentWorkerProcessStarter {
    fun start(
        temporaryDirectory: Path,
        requestPath: Path,
        responsePath: Path,
        workerMaxHeapMegabytes: Int,
    ): Process
}

internal class CatalogDocumentWorkerProcessLauncher(
    private val inheritedEnvironment: Map<String, String> = System.getenv(),
    private val launchResolver: () -> WorkerLaunch = { CatalogDocumentWorkerLaunch.resolve() },
) : CatalogDocumentWorkerProcessStarter {
    override fun start(
        temporaryDirectory: Path,
        requestPath: Path,
        responsePath: Path,
        workerMaxHeapMegabytes: Int,
    ): Process {
        val launch = launchResolver()
        val command = mutableListOf(
            javaExecutable(),
            "-Xmx${workerMaxHeapMegabytes}m",
            "-Djava.awt.headless=true",
            "-Djava.io.tmpdir=${temporaryDirectory.absolutePathString()}",
            "-Duser.home=${temporaryDirectory.absolutePathString()}",
            "-cp",
            launch.classpath,
        )
        command += launch.arguments
        command += requestPath.absolutePathString()
        command += responsePath.absolutePathString()
        return ProcessBuilder(command)
            .directory(temporaryDirectory.toFile())
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply {
                val childEnvironment = environment()
                childEnvironment.clear()
                copyAllowedEnvironment("SystemRoot", childEnvironment)
                copyAllowedEnvironment("WINDIR", childEnvironment)
                childEnvironment["TEMP"] = temporaryDirectory.absolutePathString()
                childEnvironment["TMP"] = temporaryDirectory.absolutePathString()
            }
            .start()
            .also { it.outputStream.close() }
    }

    private fun copyAllowedEnvironment(name: String, target: MutableMap<String, String>) {
        inheritedEnvironment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.let { target[name] = it.value }
    }

    private fun javaExecutable(): String {
        val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", executable).absolutePathString()
    }
}

internal data class WorkerLaunch(val classpath: String, val arguments: List<String>)

internal object CatalogDocumentWorkerLaunch {
    private const val WORKER_MAIN = "com.rfp.service.crawl.CatalogDocumentWorkerMain"

    fun resolve(mainClass: String = WORKER_MAIN): WorkerLaunch {
        val entries = runtimeClasspathEntries()
        val bootJar = entries.firstOrNull(::isSpringBootJar)
        return if (bootJar != null) {
            WorkerLaunch(
                classpath = bootJar,
                arguments = listOf(
                    "-Dloader.main=$mainClass",
                    "org.springframework.boot.loader.launch.PropertiesLauncher",
                ),
            )
        } else {
            WorkerLaunch(entries.joinToString(System.getProperty("path.separator")), listOf(mainClass))
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
    private const val VERSION = 2
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
            output.writeString(request.sourceName)
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
        val sourceName = input.readString(64).also {
            require(it == "document" || it.matches(Regex("document\\.[a-z0-9]{1,16}")))
        }
        val length = input.readInt()
        require(length in 0..settings.maxDocumentBytes)
        val bytes = ByteArray(length)
        input.readFully(bytes)
        CatalogDocumentWorkerRequest(settings, maximumDuration, maxWorkerOutputBytes, bytes, contentType, sourceName)
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
        if (arguments.size != 2) halt(CatalogDocumentWorkerExit.INFRASTRUCTURE_FAILURE)
        val requestPath = Path.of(arguments[0])
        val responsePath = Path.of(arguments[1])
        val request = try {
            CatalogDocumentWorkerProtocol.readRequest(requestPath)
        } catch (_: Throwable) {
            halt(CatalogDocumentWorkerExit.INFRASTRUCTURE_FAILURE)
        }
        val workerSource = URI.create(request.sourceName)
        try {
            val parsed = CatalogDocumentParserCore(request.settings).parse(
                request.bytes,
                request.contentType,
                workerSource,
                request.maximumDuration,
            )
            CatalogDocumentWorkerProtocol.writeSuccess(responsePath, parsed, request.maxWorkerOutputBytes)
        } catch (rejection: CatalogDocumentRejectedException) {
            if (!writeFallbackRejection(responsePath, rejection.reason, request.maxWorkerOutputBytes)) {
                halt(CatalogDocumentWorkerExit.INFRASTRUCTURE_FAILURE)
            }
        } catch (_: OutOfMemoryError) {
            if (!writeFallbackRejection(
                    responsePath,
                    DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED,
                    request.maxWorkerOutputBytes,
                )
            ) {
                halt(CatalogDocumentWorkerExit.RESOURCE_EXHAUSTED)
            }
        } catch (_: StackOverflowError) {
            if (!writeFallbackRejection(
                    responsePath,
                    DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED,
                    request.maxWorkerOutputBytes,
                )
            ) {
                halt(CatalogDocumentWorkerExit.RESOURCE_EXHAUSTED)
            }
        } catch (_: WorkerOutputLimitException) {
            if (!writeFallbackRejection(
                    responsePath,
                    DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED,
                    request.maxWorkerOutputBytes,
                )
            ) {
                halt(CatalogDocumentWorkerExit.RESOURCE_EXHAUSTED)
            }
        } catch (_: Throwable) {
            halt(CatalogDocumentWorkerExit.INFRASTRUCTURE_FAILURE)
        }
    }

    private fun writeFallbackRejection(path: Path, reason: DocumentRejectionReason, maximumBytes: Long): Boolean =
        runCatching {
            Files.deleteIfExists(path)
            CatalogDocumentWorkerProtocol.writeRejection(path, reason, maximumBytes.coerceAtLeast(64))
        }.isSuccess

    private fun halt(exitCode: Int): Nothing {
        Runtime.getRuntime().halt(exitCode)
        error("Runtime.halt returned unexpectedly")
    }
}

private fun reject(reason: DocumentRejectionReason, cause: Throwable? = null): Nothing =
    throw CatalogDocumentRejectedException(reason, cause)
