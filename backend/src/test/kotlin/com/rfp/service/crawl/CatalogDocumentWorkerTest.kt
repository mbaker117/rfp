package com.rfp.service.crawl

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CatalogDocumentWorkerTest {
    @Test
    fun `default admission allows one worker even with an enormous parent heap`() {
        val admission = CatalogDocumentWorkerAdmissionDefaults.createController(
            parentMaximumHeapBytes = Long.MAX_VALUE,
            propertyLookup = { null },
        )

        val first = admission.acquire(workerHeapMegabytes = 128, timeout = Duration.ZERO)
        try {
            assertThat(first).isNotNull
            assertThat(admission.acquire(workerHeapMegabytes = 128, timeout = Duration.ZERO)).isNull()
        } finally {
            first?.close()
        }
        val replacement = admission.acquire(workerHeapMegabytes = 128, timeout = Duration.ZERO)
        assertThat(replacement).isNotNull
        replacement?.close()
    }

    @Test
    fun `bounded process override raises the default admission limit`() {
        val admission = CatalogDocumentWorkerAdmissionDefaults.createController(
            parentMaximumHeapBytes = Long.MAX_VALUE,
            propertyLookup = { name ->
                if (name == "rfp.catalog.worker.max-processes") "2" else null
            },
        )

        val first = admission.acquire(workerHeapMegabytes = 128, timeout = Duration.ZERO)
        val second = admission.acquire(workerHeapMegabytes = 128, timeout = Duration.ZERO)
        try {
            assertThat(first).isNotNull
            assertThat(second).isNotNull
            assertThat(admission.acquire(workerHeapMegabytes = 128, timeout = Duration.ZERO)).isNull()
        } finally {
            first?.close()
            second?.close()
        }
    }

    @Test
    fun `invalid zero and absurd admission overrides fail configuration`() {
        val invalidOverrides = listOf(
            "rfp.catalog.worker.max-processes" to "not-a-number",
            "rfp.catalog.worker.max-processes" to "0",
            "rfp.catalog.worker.max-processes" to "-1",
            "rfp.catalog.worker.max-processes" to "65",
            "rfp.catalog.worker.max-aggregate-heap-mb" to "not-a-number",
            "rfp.catalog.worker.max-aggregate-heap-mb" to "0",
            "rfp.catalog.worker.max-aggregate-heap-mb" to "-1",
            "rfp.catalog.worker.max-aggregate-heap-mb" to "262145",
        )

        invalidOverrides.forEach { (propertyName, propertyValue) ->
            assertThatThrownBy {
                CatalogDocumentWorkerAdmissionDefaults.createController(
                    parentMaximumHeapBytes = Long.MAX_VALUE,
                    propertyLookup = { name -> if (name == propertyName) propertyValue else null },
                )
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(propertyName)
        }
    }

    @Test
    fun `never serializes a signed source URL into the worker request`() {
        val source = URI("https://vendor.example/manual.txt?signature=round3-super-secret&expires=999999")
        val requestSnapshot = AtomicReference<ByteArray>()
        val temporaryDirectory = AtomicReference<Path>()
        val observer = object : CatalogDocumentWorkerObserver {
            override fun onTemporaryDirectoryCreated(path: Path) {
                temporaryDirectory.set(path)
            }

            override fun onRequestWritten(path: Path) {
                requestSnapshot.set(Files.readAllBytes(path))
            }
        }

        val parsed = workerClient(observer = observer).parse("safe text".toByteArray(), "text/plain", source)

        assertThat(parsed.sourceUrl).isEqualTo(source)
        assertThat(requestSnapshot.get().toString(Charsets.ISO_8859_1))
            .doesNotContain("round3-super-secret", "vendor.example")
            .contains("document.txt")
        assertThat(temporaryDirectory.get()).doesNotExist()
    }

    @Test
    fun `launches workers with a minimal environment and private working directory`() {
        val inherited = System.getenv().toMutableMap().apply {
            put("RFP_WORKER_TEST_SECRET", "must-not-cross-process-boundary")
            put("_JAVA_OPTIONS", "-Dworker.option.leaked=true")
            put("JAVA_TOOL_OPTIONS", "-Dworker.option.leaked=true")
            put("JDK_JAVA_OPTIONS", "-Dworker.option.leaked=true")
        }
        val launcher = CatalogDocumentWorkerProcessLauncher(
            inheritedEnvironment = inherited,
            launchResolver = { CatalogDocumentWorkerLaunch.resolve(EnvironmentProbeWorkerMain::class.java.name) },
        )
        val directory = Files.createTempDirectory("catalog-worker-env-test-")
        val request = directory.resolve("request.bin")
        val response = directory.resolve("response.bin")
        Files.write(request, byteArrayOf())

        try {
            val process = launcher.start(directory, request, response, workerMaxHeapMegabytes = 64)
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue()
            assertThat(process.exitValue()).isZero()
            val probe = Files.readString(response)
            assertThat(probe)
                .doesNotContain(
                    "RFP_WORKER_TEST_SECRET",
                    "must-not-cross-process-boundary",
                    "_JAVA_OPTIONS",
                    "JAVA_TOOL_OPTIONS",
                    "JDK_JAVA_OPTIONS",
                    "worker.option.leaked=true",
                )
                .contains("user.dir=${directory.toAbsolutePath()}")
        } finally {
            CatalogDocumentWorkerArtifacts(directory, request, response).cleanup()
        }
        assertThat(directory).doesNotExist()
    }

    @Test
    fun `caps concurrent parse workers process wide`() {
        val admission = CatalogDocumentWorkerAdmissionController(
            maxConcurrentProcesses = 2,
            maxAggregateHeapMegabytes = 128,
        )
        val active = AtomicInteger()
        val maximumObserved = AtomicInteger()
        val observer = object : CatalogDocumentWorkerObserver {
            override fun onProcessStarted(handle: ProcessHandle) {
                val now = active.incrementAndGet()
                maximumObserved.accumulateAndGet(now, ::maxOf)
            }

            override fun onProcessExited(handle: ProcessHandle) {
                active.decrementAndGet()
            }
        }
        val processLauncher = helperLauncher(SlowSuccessfulWorkerMain::class.java.name)
        val clients = List(2) {
            workerClient(
                admission = admission,
                observer = observer,
                processLauncher = processLauncher,
                maximumDuration = Duration.ofSeconds(20),
                admissionTimeout = Duration.ofSeconds(10),
            )
        }
        val executor = Executors.newFixedThreadPool(4)

        try {
            val calls = (1..4).map { index ->
                executor.submit<ParsedDocument> {
                    clients[index % clients.size]
                        .parse("1000:$index".toByteArray(), "text/plain", URI("https://example.com/$index.txt"))
                }
            }
            assertThat(calls.map { it.get(30, TimeUnit.SECONDS).text }).containsExactlyInAnyOrder(
                "worker-1",
                "worker-2",
                "worker-3",
                "worker-4",
            )
        } finally {
            executor.shutdownNow()
        }

        assertThat(maximumObserved.get()).isEqualTo(2)
        assertThat(active.get()).isZero()
    }

    @Test
    fun `fails closed when worker admission times out`() {
        val admission = CatalogDocumentWorkerAdmissionController(2, 64)
        val started = CountDownLatch(1)
        val processStarts = AtomicInteger()
        val observer = object : CatalogDocumentWorkerObserver {
            override fun onProcessStarted(handle: ProcessHandle) {
                processStarts.incrementAndGet()
                started.countDown()
            }
        }
        val processLauncher = helperLauncher(SlowSuccessfulWorkerMain::class.java.name)
        val holdingClient = workerClient(
            admission = admission,
            observer = observer,
            processLauncher = processLauncher,
            maximumDuration = Duration.ofSeconds(10),
            admissionTimeout = Duration.ofSeconds(5),
        )
        val impatientClient = workerClient(
            admission = admission,
            observer = observer,
            processLauncher = processLauncher,
            maximumDuration = Duration.ofSeconds(10),
            admissionTimeout = Duration.ofMillis(50),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val holdingCall = executor.submit<ParsedDocument> {
                holdingClient.parse("1500:holder".toByteArray(), "text/plain", URI("https://example.com/holder.txt"))
            }
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()

            assertRejected(DocumentRejectionReason.WORKER_INFRASTRUCTURE_FAILURE) {
                impatientClient.parse("1:rejected".toByteArray(), "text/plain", URI("https://example.com/rejected.txt"))
            }
            assertThat(processStarts.get()).isEqualTo(1)
            assertThat(holdingCall.get(20, TimeUnit.SECONDS).text).isEqualTo("worker-holder")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `classifies a worker that exits without a response as infrastructure failure`() {
        val client = workerClient(processLauncher = helperLauncher(NoResponseWorkerMain::class.java.name))

        assertRejected(DocumentRejectionReason.WORKER_INFRASTRUCTURE_FAILURE) {
            client.parse("input".toByteArray(), "text/plain", URI("https://example.com/manual.txt"))
        }
    }

    @Test
    fun `classifies worker launch configuration errors as infrastructure failure`() {
        val launcher = CatalogDocumentWorkerProcessLauncher(
            launchResolver = { throw IllegalStateException("broken worker classpath") },
        )
        val client = workerClient(processLauncher = launcher)

        assertRejected(DocumentRejectionReason.WORKER_INFRASTRUCTURE_FAILURE) {
            client.parse("input".toByteArray(), "text/plain", URI("https://example.com/manual.txt"))
        }
    }

    @Test
    fun `kills the exact timed out process and removes its private directory`() {
        val handle = AtomicReference<ProcessHandle>()
        val directory = AtomicReference<Path>()
        val observer = lifecycleObserver(handle, directory)
        val client = workerClient(
            observer = observer,
            processLauncher = helperLauncher(SlowSuccessfulWorkerMain::class.java.name),
            maximumDuration = Duration.ofMillis(100),
        )

        assertRejected(DocumentRejectionReason.TIME_LIMIT_EXCEEDED) {
            client.parse("10000:timeout".toByteArray(), "text/plain", URI("https://example.com/timeout.txt"))
        }

        assertThat(handle.get().isAlive).isFalse()
        assertThat(directory.get()).doesNotExist()
    }

    @Test
    fun `returns infrastructure failure if a forcibly destroyed child still reports alive`() {
        val directory = AtomicReference<Path>()
        val observer = object : CatalogDocumentWorkerObserver {
            override fun onTemporaryDirectoryCreated(path: Path) {
                directory.set(path)
            }
        }
        val processStarter = CatalogDocumentWorkerProcessStarter { _, _, _, _ -> UndyingProcess() }
        val client = workerClient(
            observer = observer,
            processLauncher = processStarter,
            maximumDuration = Duration.ofMillis(10),
        )

        assertRejected(DocumentRejectionReason.WORKER_INFRASTRUCTURE_FAILURE) {
            client.parse("input".toByteArray(), "text/plain", URI("https://example.com/stuck.txt"))
        }
        assertThat(directory.get()).doesNotExist()
    }

    @Test
    fun `contains worker heap exhaustion then observes process death and directory cleanup`() {
        val handle = AtomicReference<ProcessHandle>()
        val directory = AtomicReference<Path>()
        val client = workerClient(
            settings = settings(
                maxDocumentBytes = 2 * 1024 * 1024,
                maxExpandedBytes = 64L * 1024 * 1024,
                maxArchiveEntryBytes = 64L * 1024 * 1024,
                maxTextCharacters = 64L * 1024 * 1024,
            ),
            workerMaxHeapMegabytes = 16,
            maxWorkerOutputBytes = 64L * 1024 * 1024,
            admission = CatalogDocumentWorkerAdmissionController(1, 64),
            observer = lifecycleObserver(handle, directory),
            maximumDuration = Duration.ofSeconds(20),
        )

        assertRejected(DocumentRejectionReason.RESOURCE_LIMIT_EXCEEDED) {
            client.parse(
                docxWithLargeText(24 * 1024 * 1024),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                URI("https://example.com/heap.docx"),
            )
        }

        assertThat(handle.get().isAlive).isFalse()
        assertThat(directory.get()).doesNotExist()
    }

    @Test
    fun `attempts every artifact deletion without surfacing cleanup failures`() {
        val directory = Files.createTempDirectory("catalog-worker-cleanup-test-")
        val request = Files.writeString(directory.resolve("request.bin"), "request")
        val response = Files.writeString(directory.resolve("response.bin"), "response")
        val attempted = mutableListOf<Path>()
        val failed = mutableListOf<Path>()
        val observer = object : CatalogDocumentWorkerObserver {
            override fun onCleanupFailure(path: Path, failure: Throwable) {
                failed.add(path)
                throw IllegalStateException("diagnostic observer failure")
            }
        }
        val artifacts = CatalogDocumentWorkerArtifacts(
            directory,
            request,
            response,
            deletePath = { path ->
                attempted.add(path)
                if (path == response) throw AccessDeniedException(path.toString())
                Files.deleteIfExists(path)
            },
            observer = observer,
        )

        assertThatCode(artifacts::cleanup).doesNotThrowAnyException()
        assertThat(attempted).contains(request, response, directory)
        assertThat(failed).contains(response, directory)
        assertThat(request).doesNotExist()

        Files.deleteIfExists(response)
        Files.deleteIfExists(directory)
    }

    private fun lifecycleObserver(
        handleReference: AtomicReference<ProcessHandle>,
        directory: AtomicReference<Path>,
    ) = object : CatalogDocumentWorkerObserver {
        override fun onTemporaryDirectoryCreated(path: Path) {
            directory.set(path)
        }

        override fun onProcessStarted(handle: ProcessHandle) {
            handleReference.set(handle)
        }
    }

    private fun workerClient(
        settings: CatalogDocumentParserSettings = settings(),
        maximumDuration: Duration = Duration.ofSeconds(10),
        workerMaxHeapMegabytes: Int = 64,
        maxWorkerOutputBytes: Long = 1024 * 1024,
        admissionTimeout: Duration = Duration.ofSeconds(2),
        admission: CatalogDocumentWorkerAdmissionController = CatalogDocumentWorkerAdmissionController(2, 128),
        processLauncher: CatalogDocumentWorkerProcessStarter = CatalogDocumentWorkerProcessLauncher(),
        observer: CatalogDocumentWorkerObserver = CatalogDocumentWorkerObserver.NONE,
    ) = CatalogDocumentWorkerClient(
        settings,
        maximumDuration,
        workerMaxHeapMegabytes,
        maxWorkerOutputBytes,
        admissionTimeout,
        admission,
        processLauncher,
        observer,
    )

    private fun helperLauncher(mainClass: String) = CatalogDocumentWorkerProcessLauncher(
        launchResolver = { CatalogDocumentWorkerLaunch.resolve(mainClass) },
    )

    private fun settings(
        maxDocumentBytes: Int = 1024 * 1024,
        maxExpandedBytes: Long = 8L * 1024 * 1024,
        maxArchiveEntryBytes: Long = 4L * 1024 * 1024,
        maxTextCharacters: Long = 1_000_000,
    ) = CatalogDocumentParserSettings(
        maxDocumentBytes = maxDocumentBytes,
        maxPages = 20,
        maxSheets = 10,
        maxSections = 100,
        maxExpandedBytes = maxExpandedBytes,
        maxArchiveEntryBytes = maxArchiveEntryBytes,
        maxArchiveEntries = 100,
        maxTextCharacters = maxTextCharacters,
        maxRows = 10_000,
        maxCells = 100_000,
        maxParagraphs = 10_000,
        maxTableRows = 10_000,
        maxXmlDepth = 64,
        maxXmlElements = 100_000,
        maxRuns = 100_000,
    )

    private fun assertRejected(reason: DocumentRejectionReason, call: () -> Unit) {
        val thrown = org.junit.jupiter.api.assertThrows<CatalogDocumentRejectedException> { call() }
        assertThat(thrown.reason).isEqualTo(reason)
    }

    private fun resourceBytes(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/crawl/$name")) {
        "missing fixture $name"
    }.use { it.readAllBytes() }

    private fun docxWithLargeText(characters: Int): ByteArray = rewriteZipEntry(
        resourceBytes("DMM-1000-manual.docx"),
        "word/document.xml",
    ) {
        """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p><w:r><w:t>${"x".repeat(characters)}</w:t></w:r></w:p></w:body>
            </w:document>
        """.trimIndent().toByteArray()
    }

    private fun rewriteZipEntry(bytes: ByteArray, name: String, transform: (ByteArray) -> ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            ZipOutputStream(output).use { zip ->
                generateSequence(input::getNextEntry).forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name))
                    val content = input.readAllBytes()
                    zip.write(if (entry.name == name) transform(content) else content)
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }
}

object EnvironmentProbeWorkerMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val response = Path.of(arguments[1])
        val probe = buildString {
            System.getenv().toSortedMap().forEach { (name, value) -> appendLine("$name=$value") }
            appendLine("user.dir=${System.getProperty("user.dir")}")
            appendLine("worker.option.leaked=${System.getProperty("worker.option.leaked")}")
        }
        Files.writeString(response, probe)
    }
}

object SlowSuccessfulWorkerMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val request = CatalogDocumentWorkerProtocol.readRequest(Path.of(arguments[0]))
        val value = request.bytes.toString(Charsets.UTF_8)
        Thread.sleep(value.substringBefore(':').toLong())
        val label = value.substringAfter(':')
        val workerSource = URI.create(request.sourceName)
        CatalogDocumentWorkerProtocol.writeSuccess(
            Path.of(arguments[1]),
            ParsedDocument(
                workerSource,
                "text/plain",
                listOf(DocumentFragment("worker-$label", DocumentProvenance(workerSource, section = "Document"))),
            ),
            request.maxWorkerOutputBytes,
        )
    }
}

object NoResponseWorkerMain {
    @JvmStatic
    fun main(arguments: Array<String>) = Unit
}

private class UndyingProcess : Process() {
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = InputStream.nullInputStream()
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    override fun waitFor(): Int = 0
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
    override fun exitValue(): Int = throw IllegalThreadStateException("still alive")
    override fun destroy() = Unit
    override fun destroyForcibly(): Process = this
    override fun isAlive(): Boolean = true
    override fun toHandle(): ProcessHandle = ProcessHandle.current()
}
