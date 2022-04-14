package cn.itcast.sink;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Author itcast
 * Desc 演示DataStream-Sink-基于控制台和文件
 */
public class SinkDemo01 {
    public static void main(String[] args) throws Exception {
        //TODO 0.env
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.AUTOMATIC);

        //TODO 1.source
        DataStream<String> ds = env.readTextFile("data/input/words.txt");

        //TODO 2.transformation
        //TODO 3.sink
        ds.print();
        ds.print("输出标识");
        ds.printToErr();//会在控制台上以红色输出
        ds.printToErr("输出标识");//会在控制台上以红色输出
        ds.writeAsText("data/output/result1").setParallelism(1);
        ds.writeAsText("data/output/result2").setParallelism(2);

        //TODO 4.execute
        env.execute();
    }
}