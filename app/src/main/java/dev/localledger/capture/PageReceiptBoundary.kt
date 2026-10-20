package dev.localledger.capture

object PageReceiptBoundary {
 // WebView and Compose may expose several lines as a single accessibility node.
 // A title is still a complete line: mentioning a receipt inside prose is insufficient.
 fun isReceipt(texts:List<String>,pkg:String?=null):Boolean = texts.any {text->
  // WeChat also exposes chat messages as multi-line nodes. Preserve its stricter
  // standalone-title boundary, even when the chat composer is in voice mode.
  if(pkg=="com.tencent.mm")text.trim() in ReceiptParser.pageTitles
  else text.lines().any {it.trim() in ReceiptParser.pageTitles}
 }
}

/** Debounce quiet pages, but do not let a countdown/animation postpone capture forever. */
class CaptureDebounce {
 private var firstAt:Long?=null
 fun delay(now:Long):Long {
  val first=firstAt?:now.also{firstAt=it}
  return minOf(700L,(2000L-(now-first)).coerceAtLeast(0L))
 }
 fun reset(){firstAt=null}
}
