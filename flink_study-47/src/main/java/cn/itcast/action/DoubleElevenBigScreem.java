package cn.itcast.action;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.triggers.ContinuousProcessingTimeTrigger;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Author itcast
 * Desc
 * 1.实时计算出当天零点截止到当前时间的销售总额 11月11日 00:00:00 ~ 23:59:59
 * 2.计算出各个分类的销售top3
 * 3.每秒钟更新一次统计结果
 */
public class DoubleElevenBigScreem {
    public static void main(String[] args) throws Exception {
        //TODO 1.env
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.AUTOMATIC);
        env.setParallelism(1);//方便观察
        //TODO 2.source
        DataStream<Tuple2<String, Double>> orderDS = env.addSource(new MySource());

        //TODO 3.transformation--初步聚合:每隔1s聚合一下截止到当前时间的各个分类的销售总金额
        DataStream<CategoryPojo> tempAggResult = orderDS
                //分组
                .keyBy(t -> t.f0)
                //如果直接使用之前学习的窗口按照下面的写法表示:
                //表示每隔1天计算一次
                //.window(TumblingProcessingTimeWindows.of(Time.days(1)));
                //表示每隔1s计算最近一天的数据,但是11月11日 00:01:00运行计算的是: 11月10日 00:01:00~11月11日 00:01:00 ---不对!
                //.window(SlidingProcessingTimeWindows.of(Time.days(1),Time.seconds(1)));
                //*例如中国使用UTC+08:00，您需要一天大小的时间窗口，
                //*窗口从当地时间的00:00:00开始，您可以使用{@code of(时间.天(1),时间.hours(-8))}.
                //下面的代码表示从当天的00:00:00开始计算当天的数据,缺一个触发时机/触发间隔
                //3.1定义大小为一天的窗口,第二个参数表示中国使用的UTC+08:00时区比UTC时间早
                .window(TumblingProcessingTimeWindows.of(Time.days(1), Time.hours(-8)))
                //3.2自定义触发时机/触发间隔
                .trigger(ContinuousProcessingTimeTrigger.of(Time.seconds(1)))
                //.sum()//简单聚合
                //3.3自定义聚合和结果收集
                //aggregate(AggregateFunction<T, ACC, V> aggFunction,WindowFunction<V, R, K, W> windowFunction)
                .aggregate(new PriceAggregate(), new WindowResult());//aggregate支持复杂的自定义聚合
        //3.4看一下聚合的结果
        tempAggResult.print("初步聚合的各个分类的销售总额");
        //初步聚合的各个分类的销售总额> DoubleElevenBigScreem.CategoryPojo(category=游戏, totalPrice=563.8662504982619, dateTime=2021-01-19 10:31:40)
        //初步聚合的各个分类的销售总额> DoubleElevenBigScreem.CategoryPojo(category=办公, totalPrice=876.5216500403918, dateTime=2021-01-19 10:31:40)

        //TODO 4.sink-使用上面初步聚合的结果(每隔1s聚合一下截止到当前时间的各个分类的销售总金额),实现业务需求:
        tempAggResult.keyBy(CategoryPojo::getDateTime)
                .window(TumblingProcessingTimeWindows.of(Time.seconds(1)))//每隔1s进行最终的聚合并输出结果
                //.sum//简单聚合
                .process(new FinalResultWindowProcess());//在ProcessWindowFunction中实现该复杂业务逻辑

        //TODO 5.execute
        env.execute();
    }

    /**
     * 自定义数据源实时产生订单数据Tuple2<分类, 金额>
     */
    public static class MySource implements SourceFunction<Tuple2<String, Double>> {
        private boolean flag = true;
        private String[] categorys = {"女装", "男装", "图书", "家电", "洗护", "美妆", "运动", "游戏", "户外", "家具", "乐器", "办公"};
        private Random random = new Random();

        @Override
        public void run(SourceContext<Tuple2<String, Double>> ctx) throws Exception {
            while (flag) {
                //随机生成分类和金额
                int index = random.nextInt(categorys.length);//[0~length) ==> [0~length-1]
                String category = categorys[index];//获取的随机分类
                double price = random.nextDouble() * 100;//注意nextDouble生成的是[0~1)之间的随机小数,*100之后表示[0~100)的随机小数
                ctx.collect(Tuple2.of(category, price));
                Thread.sleep(20);
            }
        }

        @Override
        public void cancel() {
            flag = false;
        }
    }

    /**
     * 自定义聚合函数,指定聚合规则
     * AggregateFunction<IN, ACC, OUT>
     */
    private static class PriceAggregate implements AggregateFunction<Tuple2<String, Double>, Double, Double> {
        //初始化累加器
        @Override
        public Double createAccumulator() {
            return 0D;//D表示double,L表示Long
        }

        //把数据累加到累加器上
        @Override
        public Double add(Tuple2<String, Double> value, Double accumulator) {
            return value.f1 + accumulator;
        }

        //获取累加结果
        @Override
        public Double getResult(Double accumulator) {
            return accumulator;
        }

        //合并各个subtask的结果
        @Override
        public Double merge(Double a, Double b) {
            return a + b;
        }
    }

    /**
     * 自定义窗口函数,指定窗口数据收集规则
     * WindowFunction<IN, OUT, KEY, W extends Window>
     */
    private static class WindowResult implements WindowFunction<Double, CategoryPojo, String, TimeWindow> {
        private FastDateFormat df = FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss");
        @Override
        //void apply(KEY key, W window, Iterable<IN> input, Collector<OUT> out)
        public void apply(String category, TimeWindow window, Iterable<Double> input, Collector<CategoryPojo> out) throws Exception {
            long currentTimeMillis = System.currentTimeMillis();
            String dateTime = df.format(currentTimeMillis);
            Double totalPrice = input.iterator().next();
            out.collect(new CategoryPojo(category,totalPrice,dateTime));
        }
    }

    /**
     * 用于存储聚合的结果
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CategoryPojo {
        private String category;//分类名称
        private double totalPrice;//该分类总销售额
        private String dateTime;// 截止到当前时间的时间,本来应该是EventTime,但是我们这里简化了直接用当前系统时间即可
    }

    /**
     * 自定义窗口完成销售总额统计和分类销售额top3统计并输出
     * abstract class ProcessWindowFunction<IN, OUT, KEY, W extends Window>
     */
    private static class FinalResultWindowProcess extends ProcessWindowFunction<CategoryPojo, Object, String, TimeWindow> {
        //注意:
        //下面的key/dateTime表示当前这1s的时间
        //elements:表示截止到当前这1s的各个分类的销售数据
        @Override
        public void process(String dateTime, Context context, Iterable<CategoryPojo> elements, Collector<Object> out) throws Exception {
            //1.实时计算出当天零点截止到当前时间的销售总额 11月11日 00:00:00 ~ 23:59:59
            double total = 0D;//用来记录销售总额
            //2.计算出各个分类的销售top3:如: "女装": 10000 "男装": 9000 "图书":8000
            //注意:这里只需要求top3,也就是只需要排前3名就行了,其他的不用管!当然你也可以每次对进来的所有数据进行排序,但是浪费!
            //所以这里直接使用小顶堆完成top3排序:
            //70
            //80
            //90
            //如果进来一个比堆顶元素还有小的,直接不要
            //如果进来一个比堆顶元素大,如85,直接把堆顶元素删掉,把85加进去并继续按照小顶堆规则排序,小的在上面,大的在下面
            //80
            //85
            //90
            //创建一个小顶堆
            Queue<CategoryPojo> queue = new PriorityQueue<>(3,//初识容量
                    //正常的排序,就是小的在前,大的在后,也就是c1>c2的时候返回1,也就是升序,也就是小顶堆
                    (c1, c2) -> c1.getTotalPrice() >= c2.getTotalPrice() ? 1 : -1);
            for (CategoryPojo element : elements) {
                double price = element.getTotalPrice();
                total += price;
                if(queue.size()< 3){
                    queue.add(element);//或offer入队
                }else{
                    if(price >= queue.peek().getTotalPrice()){//peek表示取出堆顶元素但不删除
                        //queue.remove(queue.peek());
                        queue.poll();//移除堆顶元素
                        queue.add(element);//或offer入队
                    }
                }
            }
            //代码走到这里那么queue存放的就是分类的销售额top3,但是是升序.需要改为逆序然后输出
            List<String> top3List = queue.stream()
                    .sorted((c1, c2) -> c1.getTotalPrice() >= c2.getTotalPrice() ? -1 : 1)
                    .map(c -> "分类:" + c.getCategory() + " 金额:" + c.getTotalPrice())
                    .collect(Collectors.toList());

            //3.每秒钟更新一次统计结果-也就是直接输出
            double roundResult = new BigDecimal(total).setScale(2, RoundingMode.HALF_UP).doubleValue();//四舍五入保留2位小数
            System.out.println("时间: "+dateTime +" 总金额 :" + roundResult);

            System.out.println("top3: \n" + StringUtils.join(top3List,"\n"));
        }
    }
}