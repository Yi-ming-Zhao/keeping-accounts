package dev.localledger
import dev.localledger.ui.ReleaseUpdates
import org.junit.Assert.*
import org.junit.Test
class ReleaseUpdatesTest {
 @Test fun fallbackAcceptsOnlyStableReleasesInOurRepository() {
  val prefix=ReleaseUpdates.REPOSITORY+"/releases/tag/"
  assertEquals("v0.3.1",ReleaseUpdates.latestRedirect(prefix+"v0.3.1").tag)
  for(url in listOf("https://example.com/v0.3.1",prefix+"v0.3.1-beta",prefix+"v0.3.1?redirect=elsewhere",ReleaseUpdates.REPOSITORY+".evil/releases/tag/v0.3.1")) {
   try{ReleaseUpdates.latestRedirect(url);fail("Untrusted version URL accepted")}catch(_:IllegalArgumentException){}
  }
 }
 @Test fun semanticVersionComparisonRejectsUnknownTags() {
  assertTrue(ReleaseUpdates.newer("v0.10.0","0.2.0"))
  assertFalse(ReleaseUpdates.newer("v0.2.0","0.2.0"))
  assertFalse(ReleaseUpdates.newer("v0.1.9","0.2.0"))
  assertFalse(ReleaseUpdates.newer("v0.3.0-beta","0.2.0"))
  assertFalse(ReleaseUpdates.newer("v0.2.0","0.2.1-rc1"))
  assertTrue(ReleaseUpdates.newer("v0.2.1","0.2.1-rc1"))
  assertTrue(ReleaseUpdates.newer("v0.2.2","0.2.1-rc1"))
  assertFalse(ReleaseUpdates.newer("v0.2.1","invalid"))
 }
}
