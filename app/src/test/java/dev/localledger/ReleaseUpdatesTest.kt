package dev.localledger
import dev.localledger.ui.ReleaseUpdates
import org.junit.Assert.*
import org.junit.Test
class ReleaseUpdatesTest {
 @Test fun semanticVersionComparisonRejectsUnknownTags() {
  assertTrue(ReleaseUpdates.newer("v0.10.0","0.2.0"))
  assertFalse(ReleaseUpdates.newer("v0.2.0","0.2.0"))
  assertFalse(ReleaseUpdates.newer("v0.1.9","0.2.0"))
  assertFalse(ReleaseUpdates.newer("v0.3.0-beta","0.2.0"))
 }
}
