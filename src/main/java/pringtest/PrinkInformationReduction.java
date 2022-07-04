package pringtest;

import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.io.TypeSerializerInputFormat;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple8;
import org.apache.flink.streaming.api.datastream.BroadcastStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.PrintSinkFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import pringtest.datatypes.CastleRule;
import pringtest.datatypes.TaxiFare;
import pringtest.datatypes.TreeNode;
import pringtest.generalizations.NonNumericalGeneralizer;
import pringtest.sources.TaxiFareGenerator;

import java.lang.reflect.Array;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/**
 * Class to test out flink functionality
 */
public class PrinkInformationReduction {

    private final SourceFunction<TaxiFare> source;
    private final SinkFunction<Tuple8<Object, Object, Object, Object,Object, Object, Object, Object>> sink;

    /** Creates a job using the source and sink provided. */
    public PrinkInformationReduction(SourceFunction<TaxiFare> source, SinkFunction<Tuple8<Object, Object, Object, Object,Object, Object, Object, Object>> sink) {

        this.source = source;
        this.sink = sink;
    }

    /**
     * Main method.
     *
     * @throws Exception which occurs during job execution.
     */
    public static void main(String[] args) throws Exception {

        PrinkInformationReduction job = new PrinkInformationReduction(new TaxiFareGenerator(), new PrintSinkFunction<>());

        job.execute();
    }

    /**
     * Create and execute the information reduction pipeline.
     *
     * @return {JobExecutionResult}
     * @throws Exception which occurs during job execution.
     */
    public JobExecutionResult execute() throws Exception {

        // set up streaming execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // start the data generator and arrange for watermarking
        DataStream<Tuple8<Long, Long, Long, Instant, Object, Float, Float, Float>> fares = env
                .addSource(source)
                .assignTimestampsAndWatermarks(
                        // taxi fares are in order
                        WatermarkStrategy
                                .<TaxiFare>forMonotonousTimestamps()
                                .withTimestampAssigner((fare, t) -> fare.getEventTimeMillis()))
                .map(new TaxiFareToTuple());

        MapStateDescriptor<Integer, CastleRule> ruleStateDescriptor =
                new MapStateDescriptor<>(
                        "RulesBroadcastState",
                        BasicTypeInfo.INT_TYPE_INFO,
                        TypeInformation.of(new TypeHint<CastleRule>(){}));

        // Create treeEntries for non-numerical generalizer
        ArrayList<String[]> treeEntries = new ArrayList<>();
        treeEntries.add(new String[]{"CARD","APPLE PAY"});
        treeEntries.add(new String[]{"CASH","Banknotes"});
        treeEntries.add(new String[]{"CASH","Coins"});
        treeEntries.add(new String[]{"CARD","CREDIT CARD"});

        treeEntries.add(new String[]{"CARD","MAESTRO CARD"});
        treeEntries.add(new String[]{"CARD","PAYPAL","Ratenzahlung"});
        treeEntries.add(new String[]{"CARD","PAYPAL","Auf Rechnung"});
        treeEntries.add(new String[]{"CARD","PAYPAL","Direktzahlung"});
        treeEntries.add(new String[]{"9-Euro Ticket","No payment"});

        // broadcast the rules and create the broadcast state
        ArrayList<CastleRule> rules = new ArrayList<>();
        rules.add(new CastleRule(0, CastleFunction.Generalization.NONE, false));
//        rules.add(new CastleRule(1, CastleFunction.Generalization.NONE, false));
        rules.add(new CastleRule(1, CastleFunction.Generalization.REDUCTION, false));
        rules.add(new CastleRule(2, CastleFunction.Generalization.NONE, false));
        rules.add(new CastleRule(3, CastleFunction.Generalization.NONE, false));
        rules.add(new CastleRule(4, CastleFunction.Generalization.NONNUMERICAL, treeEntries, true));
        rules.add(new CastleRule(5, CastleFunction.Generalization.AGGREGATION, Tuple2.of(0f,100f), false));
        rules.add(new CastleRule(6, CastleFunction.Generalization.NONE, Tuple2.of(0f,200f), true));
        rules.add(new CastleRule(7, CastleFunction.Generalization.NONE, Tuple2.of(10f,500f), true));

        DataStream<CastleRule> ruleStream = env.fromCollection(rules);

        // TODO add timed check. See https://nightlies.apache.org/flink/flink-docs-master/docs/dev/datastream/overview/#data-sources
//        String rulePath = "";
//        DataStream<CastleRule> ruleStream = env.readFile(new TypeSerializerInputFormat<>(TypeInformation.of(new TypeHint<CastleRule>(){})), rulePath);
        BroadcastStream<CastleRule> ruleBroadcastStream = ruleStream
                .broadcast(ruleStateDescriptor);

        DataStream<Tuple8<Object, Object, Object, Object,Object, Object, Object, Object>> privateFares = fares
                .keyBy(fare -> fare.getField(0))
                .connect(ruleBroadcastStream)
                .process(new CastleFunction())
//                .process(new CompareProcessingFunction())
                .returns(TypeInformation.of(new TypeHint<Tuple8<Object, Object, Object, Object,Object, Object, Object, Object>>(){}));

        privateFares.addSink(new TimerSink());

        // execute the transformation pipeline
        return env.execute("Data Reduction Job");
    }

    /**
     * Convert TaxiFares into a tuple8 representation
     */
    public static class TaxiFareToTuple implements MapFunction<TaxiFare, Tuple8<Long, Long, Long, Instant, Object, Float, Float, Float>> {

        @Override
        public Tuple8<Long, Long, Long, Instant, Object, Float, Float, Float> map(TaxiFare input) {
            return new Tuple8<>(input.rideId, input.taxiId, input.driverId, input.startTime,
                    input.paymentType, input.tip, input.tolls, input.totalFare);
        }
    }

    public static class TimerSink extends RichSinkFunction<Tuple8<Object, Object, Object, Object,Object, Object, Object, Object>> {

        int reportInterval = 1000;
        int counter = 0;
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;
        TreeMap<String, Integer> rangeCounter = new TreeMap<>();
        ArrayList<Long> seenIds = new ArrayList<>();

        @Override
        public void invoke(Tuple8<Object, Object, Object, Object,Object, Object, Object, Object> input, Context context) {

            long processingTime = Duration.between((Instant) input.f2, Instant.now()).toMillis();
            seenIds.add((Long) input.f0);
            counter++;

            minTime = Math.min(minTime, processingTime);
            maxTime = Math.max(maxTime, processingTime);

            if(false && processingTime >= 0 && processingTime < 50){
                rangeCounter.merge("A| 0ms - 49ms", 1, Integer::sum);
            }else if (processingTime >= 50 && processingTime < 100){
                rangeCounter.merge("B| 50ms - 99ms", 1, Integer::sum);
            }else if (processingTime >= 100 && processingTime < 200){
                rangeCounter.merge("C| 100ms - 199ms", 1, Integer::sum);
            }else if (processingTime >= 200 && processingTime < 300){
                rangeCounter.merge("D| 200ms - 299ms", 1, Integer::sum);
            }else if (processingTime >= 300 && processingTime < 400){
                rangeCounter.merge("E| 200ms - 399ms", 1, Integer::sum);
            }else if (processingTime >= 400 && processingTime < 500){
                rangeCounter.merge("F| 400ms - 499ms", 1, Integer::sum);
            }else if (processingTime >= 500 && processingTime < 600){
                rangeCounter.merge("G| 500ms - 599ms", 1, Integer::sum);
            }else if (processingTime >= 600 && processingTime < 700){
                rangeCounter.merge("H| 600ms - 699ms", 1, Integer::sum);
            }else if (processingTime >= 700 && processingTime < 800){
                rangeCounter.merge("I| 700ms - 799ms", 1, Integer::sum);
            }else if (processingTime >= 800 && processingTime < 900){
                rangeCounter.merge("J| 800ms - 899ms", 1, Integer::sum);
            }else if (processingTime >= 900 && processingTime < 1000){
                rangeCounter.merge("K| 900ms - 999ms", 1, Integer::sum);
            }else if (processingTime >= 1000 && processingTime < 1250){
                rangeCounter.merge("L| 1000ms - 1249ms", 1, Integer::sum);
            }else if (processingTime >= 1250 && processingTime < 1500){
                rangeCounter.merge("M| 1250ms - 1499ms", 1, Integer::sum);
            }else if (processingTime >= 1500 && processingTime < 2000){
                rangeCounter.merge("N| 1500ms - 1999ms", 1, Integer::sum);
            }else if (processingTime >= 2000 && processingTime < 2500){
                rangeCounter.merge("O| 2000ms - 2499ms", 1, Integer::sum);
            }else{
                rangeCounter.merge("P| 2500ms - >2500ms", 1, Integer::sum);
            }

            if(false && (counter % reportInterval) == 0){
                System.out.println(input.toString());
                System.out.println("------------------ REPORT Sink:" + this + " --------------------");
                for (Map.Entry<String, Integer> entry : rangeCounter.entrySet()) {
//                    System.out.println("| " + entry.getKey() + ":       " + entry.getValue());
                    System.out.format("| %18s | %16d |", entry.getKey().substring(3), entry.getValue());
                    System.out.println();
                }
                System.out.println("------------------------------------------");
                System.out.format("| %18s | %16d |", "Min. Time (ms)", minTime);
                System.out.println();
                System.out.format("| %18s | %16d |", "Max. Time (ms)", maxTime);
                System.out.println();
                System.out.format("| %18s | %16d |", "Total entries", counter);
                System.out.println();
                System.out.println("---------------------------------------------------");

            }else if(false){
                System.out.println(
                    input.f0 + ";" +
                    input.toString() +
//                    ";" + ((Instant) input.f3).toEpochMilli() +
//                    ";" + Instant.now().toEpochMilli() +
                    ";[Data Desc.]" +
                    ";" + (Duration.between((Instant) input.f2, Instant.now()).toMillis()));

            }

        }
    }
}
