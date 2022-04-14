package cn.itcast.feature;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.state.BroadcastState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ReadOnlyBroadcastState;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple4;
import org.apache.flink.api.java.tuple.Tuple6;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.BroadcastConnectedStream;
import org.apache.flink.streaming.api.datastream.BroadcastStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.BroadcastProcessFunction;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.util.Collector;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Author itcast
 * Desc
 */
public class BroadcastStateDemo {
    public static void main(String[] args) throws Exception {
        //TODO 1.env
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.AUTOMATIC);
        env.setParallelism(1);

        //TODO 2.source
        //-1.构建实时数据事件流--数据量较大
        //<userID, eventTime, eventType, productID>
        DataStreamSource<Tuple4<String, String, String, Integer>> eventDS = env.addSource(new MySource());

        //-2.配置流/规则流/用户信息流--数据量较小-从MySQL
        //<用户id,<姓名,年龄>>
        DataStreamSource<Map<String, Tuple2<String, Integer>>> userDS = env.addSource(new MySQLSource());

        //TODO 3.transformation
        //-1.定义状态描述器
        MapStateDescriptor<Void, Map<String, Tuple2<String, Integer>>> descriptor =
                new MapStateDescriptor<>("info", Types.VOID, Types.MAP(Types.STRING, Types.TUPLE(Types.STRING, Types.INT)));

        //-2.广播配置流
        BroadcastStream<Map<String, Tuple2<String, Integer>>> broadcastDS = userDS.broadcast(descriptor);

        //-3.将事件流和广播流进行连接
        BroadcastConnectedStream<Tuple4<String, String, String, Integer>, Map<String, Tuple2<String, Integer>>> connectDS = eventDS.connect(broadcastDS);

        //-4.处理连接后的流-根据配置流补全事件流中的用户的信息
        //BroadcastProcessFunction<IN1, IN2, OUT>
        SingleOutputStreamOperator<Tuple6<String, String, String, Integer, String, Integer>> result =
                connectDS.process(new BroadcastProcessFunction<
                        //<userID, eventTime, eventType, productID> //事件流
                        Tuple4<String, String, String, Integer>,
                        //<用户id,<姓名,年龄>> //广播流
                        Map<String, Tuple2<String, Integer>>,
                        //<用户id，eventTime，eventType，productID，姓名，年龄> //结果流 需要收集的数据
                        Tuple6<String, String, String, Integer, String, Integer>
                        >() {
                    //处理事件流中的每一个元素
                    @Override
                    public void processElement(Tuple4<String, String, String, Integer> value, ReadOnlyContext ctx, Collector<Tuple6<String, String, String, Integer, String, Integer>> out) throws Exception {
                        //value就是事件流中的数据
                        //<userID, eventTime, eventType, productID> //事件流--已经有了
                        //Tuple4<String, String, String, Integer>,
                        //目标是将value和广播流中的数据进行关联,返回结果流
                        //<用户id,<姓名,年龄>> //广播流--需要获取
                        //Map<String, Tuple2<String, Integer>>
                        //<用户id，eventTime，eventType，productID，姓名，年龄> //结果流 需要收集的数据
                        // Tuple6<String, String, String, Integer, String, Integer>

                        //获取广播流
                        ReadOnlyBroadcastState<Void, Map<String, Tuple2<String, Integer>>> broadcastState = ctx.getBroadcastState(descriptor);
                        //用户id,<姓名,年龄>
                        Map<String, Tuple2<String, Integer>> map = broadcastState.get(null);//广播流中的数据
                        if (map != null) {
                            //根据value中的用户id去map中获取用户信息
                            String userId = value.f0;
                            Tuple2<String, Integer> tuple2 = map.get(userId);
                            String username = tuple2.f0;
                            Integer age = tuple2.f1;

                            //收集数据
                            out.collect(Tuple6.of(userId, value.f1, value.f2, value.f3, username, age));
                        }
                    }

                    //更新处理广播流中的数据
                    @Override
                    public void processBroadcastElement(Map<String, Tuple2<String, Integer>> value, Context ctx, Collector<Tuple6<String, String, String, Integer, String, Integer>> out) throws Exception {
                        //value就是从MySQL中每隔5是查询出来并广播到状态中的最新数据!
                        //要把最新的数据放到state中
                        BroadcastState<Void, Map<String, Tuple2<String, Integer>>> broadcastState = ctx.getBroadcastState(descriptor);
                        broadcastState.clear();//清空旧数据
                        broadcastState.put(null, value);//放入新数据
                    }
                });

        //TODO 4.sink
        result.print();

        //TODO 5.execute
        env.execute();
    }


    /**
     * 随机事件流--数据量较大
     * 用户id,时间,类型,产品id
     * <userID, eventTime, eventType, productID>
     */
    public static class MySource implements SourceFunction<Tuple4<String, String, String, Integer>> {
        private boolean isRunning = true;

        @Override
        public void run(SourceContext<Tuple4<String, String, String, Integer>> ctx) throws Exception {
            Random random = new Random();
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            while (isRunning) {
                int id = random.nextInt(4) + 1;
                String user_id = "user_" + id;
                String eventTime = df.format(new Date());
                String eventType = "type_" + random.nextInt(3);
                int productId = random.nextInt(4);
                ctx.collect(Tuple4.of(user_id, eventTime, eventType, productId));
                Thread.sleep(500);
            }
        }

        @Override
        public void cancel() {
            isRunning = false;
        }
    }

    /**
     * 配置流/规则流/用户信息流--数据量较小
     * <用户id,<姓名,年龄>>
     */
    /*
CREATE TABLE `user_info` (
  `userID` varchar(20) NOT NULL,
  `userName` varchar(10) DEFAULT NULL,
  `userAge` int(11) DEFAULT NULL,
  PRIMARY KEY (`userID`) USING BTREE
) ENGINE=MyISAM DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC;

INSERT INTO `user_info` VALUES ('user_1', '张三', 10);
INSERT INTO `user_info` VALUES ('user_2', '李四', 20);
INSERT INTO `user_info` VALUES ('user_3', '王五', 30);
INSERT INTO `user_info` VALUES ('user_4', '赵六', 40);
     */
    public static class MySQLSource extends RichSourceFunction<Map<String, Tuple2<String, Integer>>> {
        private boolean flag = true;
        private Connection conn = null;
        private PreparedStatement ps = null;
        private ResultSet rs = null;

        @Override
        public void open(Configuration parameters) throws Exception {
            conn = DriverManager.getConnection("jdbc:mysql://localhost:3306/bigdata", "root", "root");
            String sql = "select `userID`, `userName`, `userAge` from `user_info`";
            ps = conn.prepareStatement(sql);
        }

        @Override
        public void run(SourceContext<Map<String, Tuple2<String, Integer>>> ctx) throws Exception {
            while (flag) {
                Map<String, Tuple2<String, Integer>> map = new HashMap<>();
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String userID = rs.getString("userID");
                    String userName = rs.getString("userName");
                    int userAge = rs.getInt("userAge");
                    //Map<String, Tuple2<String, Integer>>
                    map.put(userID, Tuple2.of(userName, userAge));
                }
                ctx.collect(map);
                Thread.sleep(5000);//每隔5s更新一下用户的配置信息!
            }
        }

        @Override
        public void cancel() {
            flag = false;
        }

        @Override
        public void close() throws Exception {
            if (conn != null) conn.close();
            if (ps != null) ps.close();
            if (rs != null) rs.close();
        }
    }
}
