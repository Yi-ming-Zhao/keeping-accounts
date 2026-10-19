package dev.localledger
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.localledger.ui.ReleaseUpdates
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class UpdateCheckTest {
 @Test fun publishedReleaseIsReadable()=runBlocking {
  val tag=InstrumentationRegistry.getArguments().getString("expectedTag")
  Assume.assumeNotNull(tag)
  val release=ReleaseUpdates.check()
  assertEquals(tag,release.tag)
  assertEquals(ReleaseUpdates.newer(release.tag,BuildConfig.VERSION_NAME),release.newer)
  assertEquals("${ReleaseUpdates.REPOSITORY}/releases/tag/$tag",release.url)
 }
}
