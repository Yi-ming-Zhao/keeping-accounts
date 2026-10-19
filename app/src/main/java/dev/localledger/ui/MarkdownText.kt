package dev.localledger.ui

import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin

/** Native spans only. No WebView, JavaScript, HTML plugin or remote image downloads. */
@Composable fun MarkdownText(text:String,modifier:Modifier=Modifier) {
 val color=MaterialTheme.colorScheme.onSurface.toArgb()
 val link=MaterialTheme.colorScheme.primary.toArgb()
 AndroidView(modifier=modifier,factory={context->TextView(context).apply {
  textSize=16f;setLineSpacing(7f,1.08f);setTextIsSelectable(true);includeFontPadding=false
 }},update={view->
  view.setTextColor(color);view.setLinkTextColor(link)
  val renderer=Markwon.builder(view.context).usePlugin(TablePlugin.create(view.context)).usePlugin(StrikethroughPlugin.create()).build()
  renderer.setMarkdown(view,text)
 })
}
