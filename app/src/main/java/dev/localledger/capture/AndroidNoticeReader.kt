package dev.localledger.capture

import android.app.Notification

object AndroidNoticeReader {
 fun read(notification:Notification):NoticeEnvelope {
  val extras=notification.extras
  fun text(key:String)=extras.getCharSequence(key)?.toString().orEmpty()
  val messages=Notification.MessagingStyle.Message.getMessagesFromBundleArray(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
   .map {NoticeMessage(it.senderPerson?.name?.toString(),it.text?.toString().orEmpty(),it.timestamp)}
  return NoticeEnvelope(text(Notification.EXTRA_TITLE),text(Notification.EXTRA_TITLE_BIG),text(Notification.EXTRA_TEXT),text(Notification.EXTRA_BIG_TEXT),
   extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map{it.toString()}.orEmpty(),messages,notification.flags and Notification.FLAG_GROUP_SUMMARY!=0)
 }
}
