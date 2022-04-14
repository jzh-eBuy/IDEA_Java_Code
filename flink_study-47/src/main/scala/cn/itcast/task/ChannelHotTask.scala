package cn.itcast.task

import java.lang
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import cn.itcast.bean.{ClickLog, ClickLogWide, Message}
import cn.itcast.task.{ChannelHotTask, ChannelPvUvTask, DataToWideTask}
import com.alibaba.fastjson.{JSON, JSONObject}
import org.apache.commons.lang3.SystemUtils
import org.apache.flink.api.common.eventtime.{SerializableTimestampAssigner, WatermarkStrategy}
import org.apache.flink.api.common.restartstrategy.RestartStrategies
import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.api.common.time.Time
import org.apache.flink.runtime.state.filesystem.FsStateBackend
import org.apache.flink.streaming.api.CheckpointingMode
import org.apache.flink.streaming.api.environment.CheckpointConfig
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer

/**
 * Author itcast
 * Desc 实时频道热点统计分析
 */
object ChannelHotTask {

  //定义一个样例类,用来封装频道id和访问次数
  case class ChannelRealHot(channelId: String, visited: Long)

  def process(clickLogWideDS: DataStream[ClickLogWide]) = {
    //每隔10s统计一次各个频道对应的访问量,并将结果和历史数据合并,存入到HBase
    //也就是说使用HBase存放各个频道的实时访问量,每隔10s更新一次
    import org.apache.flink.streaming.api.scala._
    //当前窗口内数据的各个频道对应的访问量
    val currentResult: DataStream[ChannelRealHot] = clickLogWideDS.map(log => {
      ChannelRealHot(log.channelID, log.count)
    })
      .keyBy(_.channelId)
      .window(TumblingEventTimeWindows.of(Time.seconds(10)))
      .reduce((a, b) => {
        ChannelRealHot(a.channelId, a.visited + b.visited)
      })

    currentResult.addSink(new SinkFunction[ChannelRealHot] {
      override def invoke(value: ChannelRealHot, context: SinkFunction.Context): Unit = {
        //-1.先查HBase该频道的上次的访问次数
        val tableName = "channel_realhot"
        val rowkey = value.channelId
        val columnFamily = "info"
        val queryColumn = "visited"

        //查出历史值(指定频道的访问次数历史值)
        //去HBase的channel_realhot表的info列族中根据channelId查询指定的列visited
        val historyVisited: String = HBaseUtil.getData(tableName,rowkey,columnFamily,queryColumn)

        var resultVisited = 0L
        //和当前值合并
        if(StringUtils.isBlank(historyVisited)){//没有历史值,那么当前窗口计算出来的结果就是该频道的访问量
          resultVisited = value.visited
        }else{
          resultVisited = value.visited + historyVisited.toLong
        }
        //存入HBase
        HBaseUtil.putData(tableName,rowkey,columnFamily,queryColumn,resultVisited.toString)
      }
    })

  }

}
