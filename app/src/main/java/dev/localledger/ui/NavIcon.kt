package dev.localledger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable fun NavIcon(name:String) {
 val color=LocalContentColor.current
 Canvas(Modifier.size(23.dp)) {
  val u=size.width/24;val stroke=Stroke(1.65f*u,cap=StrokeCap.Round,join=StrokeJoin.Round)
  fun line(a:Float,b:Float,c:Float,d:Float)=drawLine(color,Offset(a*u,b*u),Offset(c*u,d*u),1.65f*u,StrokeCap.Round)
  when(name) {
   "账本"->{drawRoundRect(color,Offset(4*u,2*u),Size(16*u,20*u),CornerRadius(2*u),style=stroke);line(8f,7f,16f,7f);line(8f,12f,16f,12f);line(8f,17f,13f,17f)}
   "分析"->{line(3f,21f,21f,21f);line(5f,16f,5f,9f);line(12f,16f,12f,3f);line(19f,16f,19f,6f)}
   "对话"->{val p=Path().apply{moveTo(4*u,3*u);lineTo(20*u,3*u);lineTo(20*u,17*u);lineTo(10*u,17*u);lineTo(4*u,22*u);close()};drawPath(p,color,style=stroke);line(8f,8f,16f,8f);line(8f,12f,13f,12f)}
   "账户"->{drawRoundRect(color,Offset(2*u,5*u),Size(20*u,15*u),CornerRadius(3*u),style=stroke);line(15f,11f,22f,11f);line(15f,15f,22f,15f);drawCircle(color,u,Offset(17*u,13*u))}
   else->{drawCircle(color,8*u,style=stroke);drawCircle(color,3*u,style=stroke);line(12f,1f,12f,4f);line(12f,20f,12f,23f);line(1f,12f,4f,12f);line(20f,12f,23f,12f)}
  }
 }
}
