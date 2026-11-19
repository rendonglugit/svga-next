package dev.readablesvga.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.animation.ValueAnimator
import android.media.SoundPool
import android.os.Looper
import dev.readablesvga.player.entities.SVGAAudioEntity
import dev.readablesvga.player.proto.AudioEntity
import dev.readablesvga.player.proto.MovieEntity
import dev.readablesvga.player.proto.MovieParams
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
        val shape = dev.readablesvga.player.proto.ShapeEntity.Builder()
            .type(dev.readablesvga.player.proto.ShapeEntity.ShapeType.RECT)
            .rect(dev.readablesvga.player.proto.ShapeEntity.RectArgs.Builder()
                .x(0f).y(0f).width(10f).height(10f).cornerRadius(0f).build()).build()
        val sprite = dev.readablesvga.player.proto.SpriteEntity.Builder().imageKey("vector")
            .frames(listOf(dev.readablesvga.player.proto.FrameEntity.Builder().alpha(1f)
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
        view.startAnimation(dev.readablesvga.player.utils.SVGARange(Int.MAX_VALUE, Int.MAX_VALUE))
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
