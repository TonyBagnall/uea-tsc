package tsml.classifiers.distance_based.distances;

import utilities.ArrayUtilities;
import utilities.StatisticalUtilities;
import utilities.params.ParamSpace;
import weka.core.Instances;

import java.util.Arrays;
import java.util.List;

import static utilities.ArrayUtilities.box;
import static utilities.ArrayUtilities.incrementalRange;

public class DistanceMeasureConfigs {
    
    private DistanceMeasureConfigs() {}
    
    public static ParamSpace buildDtwParamsV1(Instances instances) {
        ParamSpace params = new ParamSpace();
        params.add(Dtw.WARPING_WINDOW_FLAG,
                   ArrayUtilities.unique(incrementalRange(0, instances.numAttributes() - 1, 100)));
        return params;
    }

    public static ParamSpace buildDtwSpaceV1(Instances instances) {
        return new ParamSpace().add(DistanceMeasure.DISTANCE_FUNCTION_FLAG, Arrays.asList(new Dtw()), buildDtwParamsV1(instances));
    }

    public static ParamSpace buildDtwParamsV2(Instances instances) {
        return new ParamSpace().add(Dtw.WARPING_WINDOW_FLAG, ArrayUtilities.unique(ArrayUtilities.range(0, instances.numAttributes() - 1, 100)));
    }

    public static ParamSpace buildDtwSpaceV2(Instances instances) {
        return new ParamSpace().add(DistanceMeasure.DISTANCE_FUNCTION_FLAG, Arrays.asList(new Dtw()), buildDtwParamsV2(instances));
    }
    
    public static ParamSpace buildDdtwParamsV1(Instances instances) {
        return buildDtwParamsV1(instances);
    }

    public static ParamSpace buildDdtwSpaceV1(Instances instances) {
        return new ParamSpace().add(DistanceMeasure.DISTANCE_FUNCTION_FLAG, Arrays.asList(new Ddtw()), buildDdtwParamsV1(instances));
    }

    public static ParamSpace buildDdtwParamsV2(Instances instances) {
        return buildDtwParamsV2(instances);
    }

    public static ParamSpace buildDdtwSpaceV2(Instances instances) {
        return new ParamSpace().add(DistanceMeasure.DISTANCE_FUNCTION_FLAG, Arrays.asList(new Ddtw()), buildDdtwParamsV2(instances));
    }

    public static ParamSpace buildWdtwParamsV1() {
        double[] gValues = new double[100];
        for(int i = 0; i < gValues.length; i++) {
            gValues[i] = (double) i / gValues.length;
        }
        List<Double> gValuesUnique = ArrayUtilities.unique(gValues);
        ParamSpace params = new ParamSpace();
        params.add(Wdtw.G_FLAG, gValuesUnique);
        return params;
    }

    public static ParamSpace buildWdtwSpaceV1() {
        return new ParamSpace().add(DistanceMeasure.DISTANCE_FUNCTION_FLAG, Arrays.asList(new Wdtw()), buildWdtwParamsV1());
    }

    public static ParamSpace buildWdtwParamsV2() {
        double[] gValues = new double[101];
        for(int i = 0; i < gValues.length; i++) {
            gValues[i] = (double) i / 100;
        }
        List<Double> gValuesUnique = ArrayUtilities.unique(gValues);
        ParamSpace params = new ParamSpace();
        params.add(Wdtw.G_FLAG, gValuesUnique);
        return params;
    }

    public static ParamSpace buildWdtwSpaceV2() {
        return new ParamSpace().add(DistanceMeasure.DISTANCE_FUNCTION_FLAG, Arrays.asList(new Wdtw()), buildWdtwParamsV2());
    }
    
    public static ParamSpace buildWddtwParamsV1() {
        return buildWdtwParamsV1();
    }

    public static ParamSpace buildWddtwSpaceV1() {
        return new ParamSpace().add(DistanceMeasure.DISTANCE_FUNCTION_FLAG, Arrays.asList(new Wddtw()), buildWddtwParamsV1());
    }
    
    public static ParamSpace buildWddtwParamsV2() {
        return buildWdtwParamsV2();
    }

    public static ParamSpace buildWddtwSpaceV2() {
        return new ParamSpace().add(DistanceMeasure.DISTANCE_FUNCTION_FLAG, Arrays.asList(new Wddtw()), buildWddtwParamsV2());
    }
    
    public static ParamSpace buildLcssParams(Instances instances) {
        double std = StatisticalUtilities.pStdDev(instances);
        double stdFloor = std*0.2;
        double[] epsilonValues = ArrayUtilities.incrementalRange(stdFloor, std, 10);
        int[] deltaValues = ArrayUtilities.incrementalRange(0, (instances.numAttributes() - 1) / 4, 10);
        List<Double> epsilonValuesUnique = ArrayUtilities.unique(epsilonValues);
        List<Integer> deltaValuesUnique = ArrayUtilities.unique(deltaValues);
        ParamSpace params = new ParamSpace();
        params.add(Lcss.EPSILON_FLAG, epsilonValuesUnique);
        params.add(Lcss.DELTA_FLAG, deltaValuesUnique);
        return params;
    }
    
    public static ParamSpace buildLcssSpace(Instances instances) {
        return new ParamSpace().add(DistanceMeasure.DISTANCE_FUNCTION_FLAG, Arrays.asList(new Lcss()), buildLcssParams(instances));
    }

    public static ParamSpace buildTwedParams() {
        double[] nuValues = {
            // <editor-fold defaultstate="collapsed" desc="hidden for space">
            0.00001,
            0.0001,
            0.0005,
            0.001,
            0.005,
            0.01,
            0.05,
            0.1,
            0.5,
            1,// </editor-fold>
        };
        double[] lambdaValues = {
            // <editor-fold defaultstate="collapsed" desc="hidden for space">
            0,
            0.011111111,
            0.022222222,
            0.033333333,
            0.044444444,
            0.055555556,
            0.066666667,
            0.077777778,
            0.088888889,
            0.1,// </editor-fold>
        };
        List<Double> nuValuesUnique = ArrayUtilities.unique(nuValues);
        List<Double> lambdaValuesUnique = ArrayUtilities.unique(lambdaValues);
        ParamSpace params = new ParamSpace();
        params.add(Twed.LAMBDA_FLAG, lambdaValuesUnique);
        params.add(Twed.NU_FLAG, nuValuesUnique);
        return params;
    }

    public static ParamSpace buildTwedSpace() {
        return new ParamSpace().add(DistanceMeasure.DISTANCE_FUNCTION_FLAG, Arrays.asList(new Twed()),
                                    buildTwedParams());
    }

    public static ParamSpace buildErpParams(Instances instances) {
        double std = StatisticalUtilities.pStdDev(instances);
        double stdFloor = std*0.2;
        int[] bandSizeValues = ArrayUtilities.incrementalRange(0, (instances.numAttributes() - 1) / 4, 10);
        double[] penaltyValues = ArrayUtilities.incrementalRange(stdFloor, std, 10);
        List<Double> penaltyValuesUnique = ArrayUtilities.unique(penaltyValues);
        List<Integer> bandSizeValuesUnique = ArrayUtilities.unique(bandSizeValues);
        ParamSpace params = new ParamSpace();
        params.add(Erp.BAND_SIZE_FLAG, bandSizeValuesUnique);
        params.add(Erp.PENALTY_FLAG, penaltyValuesUnique);
        return params;
    }

    public static ParamSpace buildErpSpace(Instances instances) {
        return new ParamSpace().add(DistanceMeasure.DISTANCE_FUNCTION_FLAG, Arrays.asList(new Erp()),
                                    buildErpParams(instances));
    }

    public static ParamSpace buildMsmParams() {
        double[] costValues = {
            // <editor-fold defaultstate="collapsed" desc="hidden for space">
            0.01,
            0.01375,
            0.0175,
            0.02125,
            0.025,
            0.02875,
            0.0325,
            0.03625,
            0.04,
            0.04375,
            0.0475,
            0.05125,
            0.055,
            0.05875,
            0.0625,
            0.06625,
            0.07,
            0.07375,
            0.0775,
            0.08125,
            0.085,
            0.08875,
            0.0925,
            0.09625,
            0.1,
            0.136,
            0.172,
            0.208,
            0.244,
            0.28,
            0.316,
            0.352,
            0.388,
            0.424,
            0.46,
            0.496,
            0.532,
            0.568,
            0.604,
            0.64,
            0.676,
            0.712,
            0.748,
            0.784,
            0.82,
            0.856,
            0.892,
            0.928,
            0.964,
            1,
            1.36,
            1.72,
            2.08,
            2.44,
            2.8,
            3.16,
            3.52,
            3.88,
            4.24,
            4.6,
            4.96,
            5.32,
            5.68,
            6.04,
            6.4,
            6.76,
            7.12,
            7.48,
            7.84,
            8.2,
            8.56,
            8.92,
            9.28,
            9.64,
            10,
            13.6,
            17.2,
            20.8,
            24.4,
            28,
            31.6,
            35.2,
            38.8,
            42.4,
            46,
            49.6,
            53.2,
            56.8,
            60.4,
            64,
            67.6,
            71.2,
            74.8,
            78.4,
            82,
            85.6,
            89.2,
            92.8,
            96.4,
            100// </editor-fold>
        };
        List<Double> costValuesUnique = ArrayUtilities.unique(costValues);
        ParamSpace params = new ParamSpace();
        params.add(Msm.COST_FLAG, costValuesUnique);
        return params;
    }

    public static ParamSpace buildMsmSpace() {
        return new ParamSpace().add(DistanceMeasure.DISTANCE_FUNCTION_FLAG, Arrays.asList(new Msm()),
                                    buildMsmParams());
    }
}
