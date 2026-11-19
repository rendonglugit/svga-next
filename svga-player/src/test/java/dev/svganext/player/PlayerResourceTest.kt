package dev.svganext.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.animation.ValueAnimator
import android.media.SoundPool
import android.os.Looper
import dev.svganext.player.entities.SVGAAudioEntity
import dev.svganext.player.entities.SVGAVideoSpriteFrameEntity
import dev.svganext.player.proto.AudioEntity
import dev.svganext.player.proto.FrameEntity
import dev.svganext.player.proto.Layout
import dev.svganext.player.proto.MovieEntity
import dev.svganext.player.proto.MovieParams
import dev.svganext.player.proto.Transform
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSoundPool
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlayerResourceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var originalExecutor: ExecutorService
    private lateinit var executor: ThreadPoolExecutor

    @Before fun setUp() {
        SVGACache.onCreate(RuntimeEnvironment.getApplication())
        originalExecutor = SVGAParser.threadPoolExecutor
        executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        SVGAParser.setThreadPoolExecutor(executor)
    }

    @After fun tearDown() {
        executor.shutdownNow()
        SVGAParser.threadPoolExecutor = originalExecutor
        SVGASoundManager.release()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun inlineAndFileImagesDecodeWithIndependentSampling() {
        val png = png(128, 64)
        val directory = temporaryFolder.newFolder()
        directory.resolve("external.png").writeBytes(png)
        val movie = movie(mapOf("inline" to png.toByteString(), "file" to "external.png".encodeUtf8()))
        val small = SVGAVideoEntity(movie, directory, 32, 16)
        val large = SVGAVideoEntity(movie, directory, 64, 32)
        assertEquals(setOf("inline", "file"), small.imageMap.keys)
        small.imageMap.values.forEach { assertEquals(32, it.width); assertEquals(16, it.height) }
        large.imageMap.values.forEach { assertEquals(64, it.width); assertEquals(32, it.height) }
        val expectedBytes = small.imageMap.values.sumOf { it.allocationByteCount.toLong() } +
                movie.images.values.sumOf { it.size.toLong() }
        assertEquals(expectedBytes, small.estimatedMemoryBytes)
        small.clear()
        assertEquals(0L, small.estimatedMemoryBytes)
        assertEquals(64, large.imageMap.getValue("inline").width)
        large.clear()
    }

    @Test fun oneUnresolvedJsonImageDoesNotDiscardOtherImages() {
        val directory = temporaryFolder.newFolder()
        directory.resolve("found.png").writeBytes(png(8, 8))
        val json = JSONObject().put("movie", JSONObject())
            .put("images", JSONObject().put("missing", "absent.png").put("present", "found.png"))
        val video = SVGAVideoEntity(json, directory)
        assertEquals(setOf("present"), video.imageMap.keys)
        video.clear()
    }

    @Test fun queuedRequestsKeepTheirOwnFrameSize() {
        val blocker = CountDownLatch(1)
        executor.execute { blocker.await(5, TimeUnit.SECONDS) }
        val parser = SVGAParser(RuntimeEnvironment.getApplication())
        val bytes = compressed(movie(mapOf("image" to png(128, 64).toByteString())))
        val results = mutableListOf<SVGAVideoEntity>()
        val callback = object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) { results.add(videoItem) }
            override fun onError() { fail("Unexpected parse error") }
        }
        parser.setFrameSize(32, 16)
        parser.decodeFromInputStream(ByteArrayInputStream(bytes), UUID.randomUUID().toString(), callback, true)
        parser.setFrameSize(64, 32)
        parser.decodeFromInputStream(ByteArrayInputStream(bytes), UUID.randomUUID().toString(), callback, true)
        blocker.countDown()
        drain()
        assertEquals(listOf(32, 64), results.map { it.imageMap.getValue("image").width })
        results.forEach(SVGAVideoEntity::clear)
    }

    @Test fun twoDrawablesOwnDifferentStreamsAndClearingOnePreservesTheSharedEntity() {
        val video = SVGAVideoEntity(movie(emptyMap()), temporaryFolder.newFolder())
        video.imageMap["image"] = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val audio = SVGAAudioEntity(audio("voice", 10)).apply { soundID = 7 }
        video.audioList = listOf(audio)
        val player = RecordingStreamPlayer()
        val first = SVGADrawable(video, SVGAAudioPlayback(video, player))
        val second = SVGADrawable(video, SVGAAudioPlayback(video, player))
        val canvas = Canvas(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        first.cleared = false
        second.cleared = false
        first.draw(canvas)
        second.draw(canvas)
        first.draw(canvas)
        assertEquals(listOf(101, 102), player.started)
        first.pause()
        first.resume()
        assertEquals(listOf(101), player.paused)
        assertEquals(listOf(101), player.resumed)
        first.clear()
        assertEquals(listOf(101), player.stopped)
        assertNotNull(video.imageMap["image"])
        assertEquals(7, audio.soundID)
        second.currentFrame = 1
        second.draw(canvas)
        assertEquals(listOf(101), player.stopped)
        second.stop()
        assertEquals(listOf(101, 102), player.stopped)
        video.clear()
    }

    @Test fun invalidAudioAndExternalPlayCallbackCompleteExactlyOnce() {
        val header = byteArrayOf(73, 68, 51, 0).toByteString()
        val invalid = SVGAVideoEntity(movie(mapOf("voice" to header), listOf(audio("voice", 0))),
            temporaryFolder.newFolder())
        var completed = 0
        invalid.prepare({ completed++ }, null)
        assertEquals(1, completed)
        assertNull(invalid.audioList.single().soundID)
        val external = SVGAVideoEntity(movie(mapOf("voice" to header),
            listOf(audio("voice", 10), audio("voice", 10))), temporaryFolder.newFolder())
        var playCalls = 0
        external.prepare({ completed++ }, object : SVGAParser.PlayCallback {
            override fun onPlay(file: List<java.io.File>) {
                playCalls++
                assertEquals(1, file.size)
                assertArrayEquals(header.toByteArray(), file.single().readBytes())
            }
        })
        assertEquals(1, playCalls)
        assertEquals(2, completed)
        external.prepare({ completed++ }, null)
        assertEquals(3, completed)
        assertEquals(1, playCalls)
        invalid.clear()
        external.clear()
    }

    @Test
    @Config(shadows = [RejectingSoundPool::class])
    fun rejectedNativeAudioLoadDoesNotHangPreparation() {
        val header = byteArrayOf(73, 68, 51, 0).toByteString()
        SVGASoundManager.init()
        val video = SVGAVideoEntity(movie(mapOf("voice" to header), listOf(audio("voice", 10))),
            temporaryFolder.newFolder())
        var completed = 0
        video.prepare({ completed++ }, null)
        assertEquals(1, completed)
        assertNull(video.audioList.single().soundID)
        video.clear()
    }

    @Test fun truncatedCompressedInputReportsOneErrorAndClosesInput() {
        var closed = false
        val bytes = compressed(movie(emptyMap())).copyOf(5)
        val input = object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        var errors = 0
        val parser = SVGAParser(RuntimeEnvironment.getApplication())
        parser.decodeFromInputStream(input, UUID.randomUUID().toString(), object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) { fail("Truncated input succeeded") }
            override fun onError() { errors++ }
        }, true)
        drain()
        assertEquals(1, errors)
        assertTrue(closed)
    }

    @Test fun malformedMetadataReportsCauseAndShapeOnlyMovieIsAccepted() {
        val valid = movie(emptyMap())
        val invalidParams = listOf(
            MovieParams.Builder().fps(0).frames(10).viewBoxWidth(128f).viewBoxHeight(64f).build(),
            MovieParams.Builder().fps(20).frames(0).viewBoxWidth(128f).viewBoxHeight(64f).build(),
            MovieParams.Builder().fps(20).frames(10).viewBoxWidth(Float.NaN).viewBoxHeight(64f).build()
        )
        val parser = SVGAParser(RuntimeEnvironment.getApplication())
        val causes = mutableListOf<Exception>()
        invalidParams.forEach { params ->
            parser.decodeFromInputStream(ByteArrayInputStream(compressed(valid.newBuilder().params(params).build())),
                UUID.randomUUID().toString(), object : SVGAParser.ParseCompletion {
                    override fun onComplete(videoItem: SVGAVideoEntity) { fail("Invalid metadata succeeded") }
                    override fun onError() { fail("Original error cause was dropped") }
                    override fun onError(error: Exception) { causes.add(error) }
                }, true)
        }
        drain()
        assertEquals(3, causes.size)
        assertTrue(causes.all { it is IllegalArgumentException })
        val shape = dev.svganext.player.proto.ShapeEntity.Builder()
            .type(dev.svganext.player.proto.ShapeEntity.ShapeType.RECT)
            .rect(dev.svganext.player.proto.ShapeEntity.RectArgs.Builder()
                .x(0f).y(0f).width(10f).height(10f).cornerRadius(0f).build()).build()
        val sprite = dev.svganext.player.proto.SpriteEntity.Builder().imageKey("vector")
            .frames(listOf(dev.svganext.player.proto.FrameEntity.Builder().alpha(1f)
                .shapes(listOf(shape)).build())).build()
        var result: SVGAVideoEntity? = null
        parser.decodeFromInputStream(ByteArrayInputStream(compressed(valid.newBuilder().sprites(listOf(sprite)).build())),
            UUID.randomUUID().toString(), object : SVGAParser.ParseCompletion {
                override fun onComplete(videoItem: SVGAVideoEntity) { result = videoItem }
                override fun onError() { fail("Shape-only movie was rejected") }
            }, true)
        drain()
        assertTrue(result!!.imageMap.isEmpty())
        assertEquals(1, result!!.spriteList.single().frames.single().shapes.size)
        result!!.clear()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun corruptDeclaredImageDoesNotReturnPartialSuccess() {
        val parser = SVGAParser(RuntimeEnvironment.getApplication())
        var errors = 0
        parser.decodeFromInputStream(ByteArrayInputStream(compressed(movie(mapOf("broken" to "not-a-png".encodeUtf8())))),
            UUID.randomUUID().toString(), object : SVGAParser.ParseCompletion {
                override fun onComplete(videoItem: SVGAVideoEntity) { fail("Corrupt image succeeded") }
                override fun onError() { errors++ }
            }, true)
        drain()
        assertEquals(1, errors)
    }

    @Test fun fileDecodeExposesUnderlyingErrorAndRejectedSubmissionClosesOwnedStream() {
        val parser = SVGAParser(RuntimeEnvironment.getApplication())
        var errors = 0
        parser.decodeFromFile(temporaryFolder.root.resolve("missing.svga"), object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) { fail("Missing file succeeded") }
            override fun onError() { fail("File exception was dropped") }
            override fun onError(error: Exception) {
                assertTrue(error is FileNotFoundException)
                errors++
            }
        })
        drain()
        assertEquals(1, errors)
        var closed = false
        val input = object : ByteArrayInputStream(compressed(movie(emptyMap()))) {
            override fun close() { closed = true; super.close() }
        }
        executor.shutdownNow()
        parser.decodeFromInputStream(input, UUID.randomUUID().toString(), object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) { fail("Rejected task succeeded") }
            override fun onError() { errors++ }
        }, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(closed)
        assertEquals(2, errors)
    }

    @Test fun viewAntialiasDoesNotMutateSharedEntityAndStopDoesNotReportFinish() {
        val context = RuntimeEnvironment.getApplication()
        val attrs = Robolectric.buildAttributeSet().addAttribute(R.attr.antiAlias, "false").build()
        val first = SVGAImageView(context, attrs)
        val second = SVGAImageView(context)
        val video = SVGAVideoEntity(movie(emptyMap()), temporaryFolder.newFolder())
        first.setVideoItem(video)
        second.setVideoItem(video)
        assertFalse((first.drawable as SVGADrawable).antiAlias)
        assertTrue((second.drawable as SVGADrawable).antiAlias)
        assertTrue(video.antiAlias)
        var finishes = 0
        first.callback = object : SVGACallback {
            override fun onFinished() { finishes++ }
            override fun onPause() = Unit
            override fun onRepeat() = Unit
            override fun onStep(frame: Int, percentage: Double) = Unit
        }
        first.loops = 1
        first.startAnimation()
        first.stopAnimation(false)
        assertEquals(0, finishes)
        assertFalse(first.isAnimating)
        first.startAnimation()
        val field = SVGAImageView::class.java.getDeclaredField("mAnimator").apply { isAccessible = true }
        (field.get(first) as ValueAnimator).end()
        assertEquals(1, finishes)
        first.clear()
        second.clear()
        video.clear()
    }

    @Test fun frameTimingAndRangeUseLongArithmeticAndDisabledAnimationsKeepGlobalScale() {
        val context = RuntimeEnvironment.getApplication()
        val view = SVGAImageView(context)
        val video = SVGAVideoEntity(movie(emptyMap()).newBuilder().params(MovieParams.Builder()
            .fps(60).frames(60).viewBoxWidth(128f).viewBoxHeight(64f).build()).build(),
            temporaryFolder.newFolder())
        view.setVideoItem(video)
        view.loops = 1
        val animatorField = SVGAImageView::class.java.getDeclaredField("mAnimator").apply { isAccessible = true }
        view.startAnimation()
        assertEquals(1000L, (animatorField.get(view) as ValueAnimator).duration)
        view.stopAnimation(false)
        view.startAnimation(dev.svganext.player.utils.SVGARange(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(16L, (animatorField.get(view) as ValueAnimator).duration)
        view.stopAnimation(false)
        var completions = 0
        view.callback = object : SVGACallback {
            override fun onFinished() { completions++ }
            override fun onPause() = Unit
            override fun onRepeat() = Unit
            override fun onStep(frame: Int, percentage: Double) = Unit
        }
        val setter = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val oldScale = ValueAnimator.getDurationScale()
        try {
            setter.invoke(null, 0f)
            view.startAnimation()
            assertEquals(0f, ValueAnimator.getDurationScale())
            assertEquals(59, (view.drawable as SVGADrawable).currentFrame)
            assertNull(animatorField.get(view))
            assertEquals(1, completions)
        } finally {
            setter.invoke(null, oldScale)
            view.clear()
            video.clear()
        }
    }

    @Test
    @Config(shadows = [AcceptingSoundPool::class])
    fun releasingManagerTerminatesPendingLoadCallbacks() {
        val file = temporaryFolder.newFile().apply { writeBytes(byteArrayOf(73, 68, 51, 0)) }
        SVGASoundManager.init()
        var completions = 0
        FileInputStream(file).use { input ->
            val sample = SVGASoundManager.load(object : SVGASoundManager.SVGASoundCallBack {
                override fun onComplete(status: Int) {
                    assertEquals(-1, status)
                    completions++
                }
            }, input.fd, 0, file.length(), 1)
            assertTrue(sample > 0)
        }
        assertEquals(0, completions)
        SVGASoundManager.release()
        SVGASoundManager.release()
        assertEquals(1, completions)
    }

    @Test
    @Config(shadows = [AcceptingSoundPool::class])
    fun volumeStillReachesBothOwnersAfterLoadCallbacksHaveBeenReleased() {
        SVGASoundManager.init()
        val poolField = SVGASoundManager::class.java.getDeclaredField("soundPool").apply { isAccessible = true }
        val pool = poolField.get(SVGASoundManager) as SoundPool
        val header = byteArrayOf(73, 68, 51, 0).toByteString()
        val video = SVGAVideoEntity(movie(mapOf("voice" to header), listOf(audio("voice", 10))),
            temporaryFolder.newFolder())
        var completions = 0
        video.prepare({ completions++ }, null)
        shadowOf(pool).notifyPathLoaded("sample-1", true)
        assertEquals(1, completions)
        val other = SVGAVideoEntity(movie(mapOf("voice" to header), listOf(audio("voice", 10))),
            temporaryFolder.newFolder())
        other.prepare({ completions++ }, null)
        shadowOf(pool).notifyPathLoaded("sample-2", true)
        assertEquals(2, completions)
        val player = RecordingStreamPlayer()
        val playbacks = listOf(SVGAAudioPlayback(video, player), SVGAAudioPlayback(video, player),
            SVGAAudioPlayback(other, player))
        playbacks.forEach { it.onFrame(0) }
        SVGASoundManager.setVolume(0.3f, video)
        assertEquals(mapOf(101 to 0.3f, 102 to 0.3f), player.volumes)
        SVGASoundManager.setVolume(0.7f)
        assertEquals(mapOf(101 to 0.7f, 102 to 0.7f, 103 to 0.7f), player.volumes)
        playbacks.forEach(SVGAAudioPlayback::stop)
        video.clear()
        other.clear()
    }

    @Test
    @Config(shadows = [ThrowingReleaseSoundPool::class])
    fun entityCleanupClearsJavaReferencesEvenIfNativeReleaseThrows() {
        val video = SVGAVideoEntity(movie(emptyMap()), temporaryFolder.newFolder())
        video.imageMap["image"] = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        video.soundPool = SoundPool.Builder().setMaxStreams(1).build()
        try {
            video.clear()
            fail("Native release failure was swallowed")
        } catch (e: IllegalStateException) {
            assertEquals("test native release failure", e.message)
        }
        assertTrue(video.imageMap.isEmpty())
        assertTrue(video.audioList.isEmpty())
        assertNull(video.movieItem)
        assertNull(video.soundPool)
        video.clear()
    }

    @Test fun cancelingDownloadDisconnectsBlockedReadWithoutDeliveringLateCallbacks() {
        val enteredRead = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        lateinit var connection: HttpURLConnection
        val url = URL(null, "https://svga.test/animation.svga", object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun usingProxy(): Boolean = false
                override fun disconnect() { disconnected.countDown() }
                override fun getInputStream(): InputStream = object : InputStream() {
                    override fun read(): Int = throw UnsupportedOperationException()
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        enteredRead.countDown()
                        if (!disconnected.await(3, TimeUnit.SECONDS)) throw IOException("test read deadline")
                        throw IOException("test connection disconnected")
                    }
                }
            }.also { connection = it }
        })
        var callbacks = 0
        val cancel = SVGAParser.FileDownloader().resume(url, { callbacks++ }, { callbacks++ })
        assertTrue(enteredRead.await(3, TimeUnit.SECONDS))
        cancel()
        drain()
        assertEquals(0, callbacks)
        assertEquals(20_000, connection.readTimeout)
        assertEquals(20_000, connection.connectTimeout)
    }

    @Test fun prefetchPublishesBytesAndPlaybackReusesThemWithoutNetwork() = verifyPrefetch(false)

    @Test fun prefetchedZipPlaysWithoutDownloadingAgain() = verifyPrefetch(true)

    private fun verifyPrefetch(zip: Boolean) {
        val parser = SVGAParser(RuntimeEnvironment.getApplication())
        val url = URL("https://example.invalid/${UUID.randomUUID()}.svga")
        val source = movie(emptyMap())
        val bytes = if (zip) ByteArrayOutputStream().use { output ->
            java.util.zip.ZipOutputStream(output).use { archive ->
                archive.putNextEntry(java.util.zip.ZipEntry("movie.binary"))
                archive.write(MovieEntity.ADAPTER.encode(source))
                archive.closeEntry()
            }
            output.toByteArray()
        } else compressed(source)
        var downloads = 0
        parser.fileDownloader = object : SVGAParser.FileDownloader() {
            override fun resume(url: URL, complete: (InputStream) -> Unit, failure: (Exception) -> Unit): () -> Unit {
                downloads++
                ByteArrayInputStream(bytes).use(complete)
                return {}
            }
        }
        val files = mutableListOf<java.io.File>()
        try {
            repeat(2) {
                parser.prefetchToDisk(url) { files.add(it.getOrThrow()) }
                drain()
            }
            assertEquals(1, downloads)
            assertEquals(2, files.size)
            assertEquals(files[0], files[1])
            assertArrayEquals(bytes, files[0].readBytes())
            var video: SVGAVideoEntity? = null
            parser.decodeFromURL(url, object : SVGAParser.ParseCompletion {
                override fun onComplete(videoItem: SVGAVideoEntity) { video = videoItem }
                override fun onError() { fail("Prefetched file did not play") }
            })
            repeat(2) { drain() }
            assertNotNull(video)
            assertEquals(1, downloads)
            video?.clear()
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test fun prefetchPrefersExtractedDirectoryOverRawFile() {
        val parser = SVGAParser(RuntimeEnvironment.getApplication())
        val url = URL("https://example.invalid/${UUID.randomUUID()}.svga")
        val key = SVGACache.buildCacheKey(url)
        val directory = SVGACache.buildCacheDir(key).apply { mkdirs() }
        val raw = SVGACache.buildSvgaFile(key)
        parser.fileDownloader = object : SVGAParser.FileDownloader() {
            override fun resume(url: URL, complete: (InputStream) -> Unit, failure: (Exception) -> Unit): () -> Unit =
                error("Disk prefetch unexpectedly started a download")
        }
        try {
            raw.writeBytes(compressed(movie(emptyMap())))
            directory.resolve("movie.binary").writeBytes(MovieEntity.ADAPTER.encode(movie(emptyMap())))
            var artifact: java.io.File? = null
            parser.prefetchToDisk(url) { artifact = it.getOrThrow() }
            drain()
            assertEquals(directory, artifact)
        } finally {
            directory.deleteRecursively()
            raw.delete()
        }
    }

    @Test fun urlEntryReturnsWhileSameKeyCacheWriterIsBlocked() {
        val parser = SVGAParser(RuntimeEnvironment.getApplication())
        val url = URL("https://example.invalid/${UUID.randomUUID()}.svga")
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val downloads = java.util.concurrent.atomic.AtomicInteger()
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor()
        parser.fileDownloader = object : SVGAParser.FileDownloader() {
            override fun resume(url: URL, complete: (InputStream) -> Unit, failure: (Exception) -> Unit): () -> Unit {
                if (downloads.incrementAndGet() == 1) {
                    complete(object : InputStream() {
                        override fun read(): Int {
                            writing.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            return -1
                        }
                    })
                }
                return {}
            }
        }
        val playback = SVGAParser(RuntimeEnvironment.getApplication()).apply { fileDownloader = parser.fileDownloader }
        val cancel = parser.prefetchToDisk(url) { fail("Cancelled prefetch delivered a result") }
        try {
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            caller.submit { playback.decodeFromURL(url, null)?.invoke() }.get(5, TimeUnit.SECONDS)
            assertEquals(1, downloads.get())
        } finally {
            cancel()
            release.countDown()
            caller.shutdownNow()
        }
        drain()
        assertFalse(SVGACache.buildSvgaFile(SVGACache.buildCacheKey(url)).exists())
    }

    @Test fun cancelledInFlightPrefetchRejectsLateDownload() {
        val parser = SVGAParser(RuntimeEnvironment.getApplication())
        val url = URL("https://example.invalid/${UUID.randomUUID()}.svga")
        var callbacks = 0
        var cancellations = 0
        lateinit var deliver: (InputStream) -> Unit
        parser.fileDownloader = object : SVGAParser.FileDownloader() {
            override fun resume(url: URL, complete: (InputStream) -> Unit, failure: (Exception) -> Unit): () -> Unit {
                deliver = complete
                return { cancellations++ }
            }
        }
        val cancel = parser.prefetchToDisk(url) { callbacks++ }
        drain()
        cancel()
        ByteArrayInputStream(compressed(movie(emptyMap()))).use(deliver)
        drain()
        assertEquals(1, cancellations)
        assertEquals(0, callbacks)
        assertFalse(SVGACache.buildSvgaFile(SVGACache.buildCacheKey(url)).exists())
    }

    @Test fun emptyPrefetchFailsWithoutPublishingCacheFile() {
        val parser = SVGAParser(RuntimeEnvironment.getApplication())
        val url = URL("https://example.invalid/${UUID.randomUUID()}.svga")
        parser.fileDownloader = object : SVGAParser.FileDownloader() {
            override fun resume(url: URL, complete: (InputStream) -> Unit, failure: (Exception) -> Unit): () -> Unit {
                ByteArrayInputStream(byteArrayOf()).use(complete)
                return {}
            }
        }
        var error: Throwable? = null
        parser.prefetchToDisk(url) { error = it.exceptionOrNull() }
        drain()
        assertTrue(error is IOException)
        assertFalse(SVGACache.buildSvgaFile(SVGACache.buildCacheKey(url)).exists())
    }

    @Test fun cancelledQueuedPrefetchNeverStartsDownload() {
        val parser = SVGAParser(RuntimeEnvironment.getApplication())
        val blocker = CountDownLatch(1)
        var downloads = 0
        var callbacks = 0
        parser.fileDownloader = object : SVGAParser.FileDownloader() {
            override fun resume(url: URL, complete: (InputStream) -> Unit, failure: (Exception) -> Unit): () -> Unit {
                downloads++
                return {}
            }
        }
        executor.execute { blocker.await(5, TimeUnit.SECONDS) }
        try {
            parser.prefetchToDisk(URL("https://example.invalid/${UUID.randomUUID()}.svga")) { callbacks++ }.invoke()
        } finally {
            blocker.countDown()
        }
        drain()
        assertEquals(0, downloads)
        assertEquals(0, callbacks)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun sharedDownloadSurvivesOneCancellationAndDecodesEachSize() {
        val url = URL("https://example.invalid/${UUID.randomUUID()}.svga")
        var starts = 0
        var stops = 0
        lateinit var deliver: (InputStream) -> Unit
        val backend = object : SVGAParser.FileDownloader() {
            override fun resume(url: URL, complete: (InputStream) -> Unit, failure: (Exception) -> Unit): () -> Unit {
                starts++
                deliver = complete
                return { stops++ }
            }
        }
        fun parser(width: Int) = SVGAParser(RuntimeEnvironment.getApplication()).apply {
            fileDownloader = backend
            setFrameSize(width, width / 2)
        }
        val videos = mutableListOf<SVGAVideoEntity>()
        val callback = object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) { videos.add(videoItem) }
            override fun onError() { fail("Shared download failed") }
        }
        val cancelled = parser(16).decodeFromURL(url, object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) { fail("Cancelled subscriber received a video") }
            override fun onError() { fail("Cancelled subscriber received an error") }
        })!!
        parser(32).decodeFromURL(url, callback)
        parser(64).decodeFromURL(url, callback)
        try {
            cancelled()
            assertEquals(0, stops)
            assertEquals(1, starts)
            ByteArrayInputStream(compressed(movie(mapOf("image" to png(128, 64).toByteString())))).use(deliver)
            drain()
            assertEquals(listOf(32, 64), videos.map { it.imageMap.getValue("image").width })
            assertNotSame(videos[0], videos[1])
        } finally {
            videos.forEach(SVGAVideoEntity::clear)
        }
    }

    @Test fun lastSharedSubscriberCancelsTransportAndLateResultCannotCompleteReplacement() {
        val parser = SVGAParser(RuntimeEnvironment.getApplication())
        val url = URL("https://example.invalid/${UUID.randomUUID()}.svga")
        val deliveries = mutableListOf<(InputStream) -> Unit>()
        var stops = 0
        var completions = 0
        parser.fileDownloader = object : SVGAParser.FileDownloader() {
            override fun resume(url: URL, complete: (InputStream) -> Unit, failure: (Exception) -> Unit): () -> Unit {
                deliveries.add(complete)
                return { stops++ }
            }
        }
        val callback = object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) { completions++; videoItem.clear() }
            override fun onError() { fail("Unexpected parse failure") }
        }
        val first = parser.decodeFromURL(url, callback)!!
        val second = parser.decodeFromURL(url, callback)!!
        assertEquals(1, deliveries.size)
        first()
        assertEquals(0, stops)
        second()
        second()
        assertEquals(1, stops)
        parser.decodeFromURL(url, callback)
        assertEquals(2, deliveries.size)
        val bytes = compressed(movie(emptyMap()))
        ByteArrayInputStream(bytes).use(deliveries[0])
        drain()
        assertEquals(0, completions)
        ByteArrayInputStream(bytes).use(deliveries[1])
        drain()
        assertEquals(1, completions)
    }

    private fun drain() {
        executor.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun movie(images: Map<String, ByteString>, audios: List<AudioEntity> = emptyList()): MovieEntity =
        MovieEntity.Builder().version("2.0").params(MovieParams.Builder()
            .viewBoxWidth(128f).viewBoxHeight(64f).frames(10).fps(20).build())
            .images(images).audios(audios).build()

    private fun audio(key: String, totalTime: Int): AudioEntity = AudioEntity.Builder()
        .audioKey(key).startFrame(0).endFrame(9).startTime(0).totalTime(totalTime).build()

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        output.toByteArray()
    }

    @Test
    fun frameDataPacksTransformAndLayoutIntoFloat11() {
        val protoTransform = Transform.Builder()
            .a(1.5f).b(0.2f).c(0.3f).d(2.0f).tx(10f).ty(20f).build()
        val protoLayout = Layout.Builder()
            .x(5f).y(6f).width(100f).height(200f).build()
        val protoFrame = FrameEntity.Builder()
            .alpha(0.85f)
            .transform(protoTransform)
            .layout(protoLayout)
            .build()

        val frame = SVGAVideoSpriteFrameEntity(protoFrame)
        assertEquals(SVGAVideoSpriteFrameEntity.FRAME_DATA_SIZE, frame.frameData.size)
        assertEquals(1.5f, frame.frameData[SVGAVideoSpriteFrameEntity.INDEX_A], 0.001f)
        assertEquals(0.3f, frame.frameData[SVGAVideoSpriteFrameEntity.INDEX_C], 0.001f)
        assertEquals(10f, frame.frameData[SVGAVideoSpriteFrameEntity.INDEX_TX], 0.001f)
        assertEquals(0.2f, frame.frameData[SVGAVideoSpriteFrameEntity.INDEX_B], 0.001f)
        assertEquals(2.0f, frame.frameData[SVGAVideoSpriteFrameEntity.INDEX_D], 0.001f)
        assertEquals(20f, frame.frameData[SVGAVideoSpriteFrameEntity.INDEX_TY], 0.001f)
        assertEquals(0.85f, frame.frameData[SVGAVideoSpriteFrameEntity.INDEX_ALPHA], 0.001f)
        assertEquals(5f, frame.frameData[SVGAVideoSpriteFrameEntity.INDEX_LAYOUT_X], 0.001f)
        assertEquals(6f, frame.frameData[SVGAVideoSpriteFrameEntity.INDEX_LAYOUT_Y], 0.001f)
        assertEquals(100f, frame.frameData[SVGAVideoSpriteFrameEntity.INDEX_LAYOUT_WIDTH], 0.001f)
        assertEquals(200f, frame.frameData[SVGAVideoSpriteFrameEntity.INDEX_LAYOUT_HEIGHT], 0.001f)

        assertEquals(0.85, frame.alpha, 0.001)
        assertEquals(100.0, frame.layout.width, 0.001)
        assertEquals(200.0, frame.layout.height, 0.001)

        val matrixValues = FloatArray(9)
        frame.transform.getValues(matrixValues)
        assertEquals(1.5f, matrixValues[0], 0.001f)
        assertEquals(0.3f, matrixValues[1], 0.001f)
        assertEquals(10f, matrixValues[2], 0.001f)
        assertEquals(0.2f, matrixValues[3], 0.001f)
        assertEquals(2.0f, matrixValues[4], 0.001f)
        assertEquals(20f, matrixValues[5], 0.001f)
    }

    @Test
    fun frameDataPacksJsonTransformAndLayoutIntoFloat11() {
        val json = JSONObject()
            .put("alpha", 0.5)
            .put("transform", JSONObject().put("a", 2.0).put("b", 0.0).put("c", 0.0).put("d", 2.0).put("tx", 50.0).put("ty", 60.0))
            .put("layout", JSONObject().put("x", 1.0).put("y", 2.0).put("width", 300.0).put("height", 400.0))

        val frame = SVGAVideoSpriteFrameEntity(json)
        assertEquals(SVGAVideoSpriteFrameEntity.FRAME_DATA_SIZE, frame.frameData.size)
        assertEquals(2.0f, frame.frameData[SVGAVideoSpriteFrameEntity.INDEX_A], 0.001f)
        assertEquals(50.0f, frame.frameData[SVGAVideoSpriteFrameEntity.INDEX_TX], 0.001f)
        assertEquals(60.0f, frame.frameData[SVGAVideoSpriteFrameEntity.INDEX_TY], 0.001f)
        assertEquals(0.5f, frame.frameData[SVGAVideoSpriteFrameEntity.INDEX_ALPHA], 0.001f)
        assertEquals(300.0f, frame.layoutWidth, 0.001f)
        assertEquals(400.0f, frame.layoutHeight, 0.001f)
    }

    private fun compressed(movie: MovieEntity): ByteArray = ByteArrayOutputStream().use { output ->
        DeflaterOutputStream(output).use { it.write(MovieEntity.ADAPTER.encode(movie)) }
        output.toByteArray()
    }

    private class RecordingStreamPlayer : SVGAAudioPlayback.StreamPlayer {
        val started = mutableListOf<Int>()
        val stopped = mutableListOf<Int>()
        val paused = mutableListOf<Int>()
        val resumed = mutableListOf<Int>()
        val volumes = mutableMapOf<Int, Float>()
        override fun play(sampleId: Int): Int = (101 + started.size).also(started::add)
        override fun stop(streamId: Int) { stopped.add(streamId) }
        override fun pause(streamId: Int) { paused.add(streamId) }
        override fun resume(streamId: Int) { resumed.add(streamId) }
        override fun setVolume(streamId: Int, volume: Float) { volumes[streamId] = volume }
    }

    @Implements(SoundPool::class)
    class RejectingSoundPool : ShadowSoundPool() {
        @Implementation
        protected fun load(fd: FileDescriptor, offset: Long, length: Long, priority: Int): Int = 0
    }

    @Implements(SoundPool::class)
    class AcceptingSoundPool : ShadowSoundPool() {
        private var nextId = 1
        @Implementation
        protected fun load(fd: FileDescriptor, offset: Long, length: Long, priority: Int): Int =
            super.load("sample-${nextId++}", priority)
    }

    @Implements(SoundPool::class)
    class ThrowingReleaseSoundPool : ShadowSoundPool() {
        @Implementation
        protected fun release(): Unit = throw IllegalStateException("test native release failure")
    }
}
