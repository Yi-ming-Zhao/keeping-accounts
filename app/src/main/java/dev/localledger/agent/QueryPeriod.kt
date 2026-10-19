package dev.localledger.agent
import org.json.JSONObject
import java.time.*

/** Date arithmetic belongs to the phone, not model-generated epoch calculations. */
object QueryPeriod {
 fun resolve(args:JSONObject,defaultPeriod:String="all",today:LocalDate=LocalDate.now(),zone:ZoneId=ZoneId.systemDefault()):Pair<Long,Long> {
  fun midnight(day:LocalDate)=day.atStartOfDay(zone).toInstant().toEpochMilli()
  if(args.has("month")) {
   val month=YearMonth.parse(args.getString("month"))
   return midnight(month.atDay(1)) to midnight(month.plusMonths(1).atDay(1))
  }
  if(!args.has("period")&&(args.has("start")||args.has("end"))) {
   val start=args.optLong("start",0);val end=args.optLong("end",Long.MAX_VALUE)
   require(start>=0&&end>start);return start to end
  }
  return when(args.optString("period",defaultPeriod)) {
   "all"->0L to Long.MAX_VALUE
   "today"->midnight(today) to midnight(today.plusDays(1))
   "this_month"->midnight(today.withDayOfMonth(1)) to midnight(today.withDayOfMonth(1).plusMonths(1))
   "last_month"->midnight(today.withDayOfMonth(1).minusMonths(1)) to midnight(today.withDayOfMonth(1))
   "last_7_days"->midnight(today.minusDays(6)) to midnight(today.plusDays(1))
   else->error("period 请选择 today、this_month、last_month、last_7_days 或 all")
  }
 }
}
