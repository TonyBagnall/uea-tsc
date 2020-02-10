
/*
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package tsml.classifiers.interval_based;

import evaluation.evaluators.CrossValidationEvaluator;
import experiments.data.DatasetLoading;
import machine_learning.classifiers.TimeSeriesTree;
import tsml.classifiers.Checkpointable;
import tsml.classifiers.EnhancedAbstractClassifier;
import tsml.classifiers.TrainTimeContractable;
import tsml.transformers.Catch22;
import utilities.ClassifierTools;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.Classifier;
import weka.classifiers.trees.RandomTree;
import weka.core.*;

import java.io.File;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Implementation of the catch22 Interval Forest algorithm
 *
 * author Matthew Middlehurst
 * expanded from original TSF code by Anthony Bagnall
 * date 01/02/20
 **/

public class C22IF extends EnhancedAbstractClassifier
        implements TechnicalInformationHandler, TrainTimeContractable, Checkpointable {

    /** Primary parameters potentially tunable */
    private int numClassifiers = 500;

    /** Amount of attributes to be subsampled and related data storage. */
    private int attSubsampleSize = 8;
    private int numAttributes = 25;
    private int startNumAttributes;
    private ArrayList<ArrayList<Integer>> subsampleAtts;

    /** Normalise outlier catch22 features which break on data not normalised */
    private boolean outlierNorm = true;

    /** Use mean,stdev,slope as well as catch22 features */
    private boolean useSummaryStats = true;

    /** IntervalsFinders sets parameter values in buildClassifier if -1. */
    /** Num intervals selected per tree built */
    private int numIntervals = -1;
    private Function<Integer,Integer> numIntervalsFinder = (numAtts) -> (int)(Math.sqrt(numAtts));

    /** Secondary parameters */
    /** Mainly there to avoid single item intervals, which have no slope or std dev*/
    private int minIntervalLength = 3;
    private Function<Integer,Integer> minIntervalLengthFinder;
    private int maxIntervalLength = -1;
    private Function<Integer,Integer> maxIntervalLengthFinder = (numAtts) -> numAtts;

    /** Ensemble members of base classifier, default to TimeSeriesTree */
    private ArrayList<Classifier> trees;
    private Classifier base= new TimeSeriesTree();

    /** for each classifier [i]  interval j  starts at intervals.get(i)[j][0] and
     ends  at  intervals.get(i)[j][1] */
    private  ArrayList<int[][]> intervals;

    /**Holding variable for test classification in order to retain the header info*/
    private Instances testHolder;

    /** voteEnsemble determines whether to aggregate classifications or
     * probabilities when predicting */
    private boolean voteEnsemble = true;

    /** Flags and data required if Bagging **/
    private boolean bagging = false;
    private boolean[][] inBag;
    private int[] oobCounts;
    private double[][] trainDistributions;

    /** If trainAccuracy is required, there are three mechanisms to obtain it:
     * 1. bagging == true: use the OOB accuracy from the final model
     * 2. bagging == false,estimator=CV: do a 10x CV on the train set with a clone
     * of this classifier
     * 3. bagging == false,estimator=OOB: build an OOB model just to get the OOB
     * accuracy estimate
     */
    enum EstimatorMethod{CV,OOB}
    private EstimatorMethod estimator = EstimatorMethod.CV;

    /** Flags and data required if Checkpointing **/
    private boolean checkpoint = false;
    private String checkpointPath;
    private long checkpointTime = 0;
    private long checkpointTimeDiff = 0;

    /** Flags and data required if Contracting **/
    private boolean trainTimeContract = false;
    private long contractTime = 0;
    private int maxClassifiers = 500;

    /** Stored for temporal importance curves **/
    private int seriesLength;

    /** Transformer used to obtain catch22 features **/
    private Catch22 c22;

    protected static final long serialVersionUID = 222556L;

    public C22IF(){
        super(CAN_ESTIMATE_OWN_PERFORMANCE);
    }

    public void setNumTrees(int t){
        numClassifiers = t;
    }

    public void setAttSubsampleSize(int a) { attSubsampleSize = a; }

    public void setUseSummaryStats(boolean b) { useSummaryStats = b; }

    public void setNumIntervalsFinder(Function<Integer,Integer> f){ numIntervalsFinder = f; }

    public void setOutlierNorm(boolean b) { outlierNorm = b; }

    /**
     * @param c a base classifier constructed elsewhere and cloned into ensemble
     */
    public void setBaseClassifier(Classifier c){
        base=c;
    }

    /**
     * @param b boolean to set vote ensemble
     */
    public void setProbabilityEnsemble(boolean b){
        voteEnsemble = b;
    }

    public void setBagging(boolean b){
        bagging = b;
    }

    /**
     * @return String written to results files
     */
    @Override
    public String getParameters() {
        int nt = numClassifiers;
        if (trees != null) nt = trees.size();
        String temp=super.getParameters()+",numTrees,"+nt+",attSubsampleSize,"+attSubsampleSize+
                ",outlierNorm,"+outlierNorm+",basicSummaryStats,"+useSummaryStats+",numIntervals,"+numIntervals+
                ",minIntervalLength,"+minIntervalLength+",maxIntervalLength,"+maxIntervalLength+
                ",baseClassifier,"+base.getClass().getSimpleName()+",voting,"+voteEnsemble+",bagging,"+bagging+
                ",estimator,"+estimator.name()+",contractTime,"+contractTime;
        return temp;
    }

    /**
     * paper defining TSF. need update when published
     * @return TechnicalInformation
     */
    @Override
    public TechnicalInformation getTechnicalInformation() {
//        TechnicalInformation result;
//        result = new TechnicalInformation(TechnicalInformation.Type.ARTICLE);
//        result.setValue(TechnicalInformation.Field.AUTHOR, "H. Deng, G. Runger, E. Tuv and M. Vladimir");
//        result.setValue(TechnicalInformation.Field.YEAR, "2013");
//        result.setValue(TechnicalInformation.Field.TITLE, "A time series forest for classification and " +
//                "feature extraction");
//        result.setValue(TechnicalInformation.Field.JOURNAL, "Information Sciences");
//        result.setValue(TechnicalInformation.Field.VOLUME, "239");
//        result.setValue(TechnicalInformation.Field.PAGES, "142-153");
//        return result;

        return null;
    }

    /**
     * main buildClassifier
     * @param data
     * @throws Exception
     */
    @Override
    public void buildClassifier(Instances data) throws Exception {
    /** Build Stage:
     *  Builds the final classifier with or without bagging.
     */
        trainResults.setBuildTime(System.nanoTime());
        // can classifier handle the data?
        getCapabilities().testWithFail(data);

        File file = new File(checkpointPath + "C22IF" + seed + ".ser");
        //if checkpointing and serialised files exist load said files
        if (checkpoint && file.exists()){
            //path checkpoint files will be saved to
            if(debug)
                System.out.println("Loading from checkpoint file");
            loadFromFile(checkpointPath + "C22IF" + seed + ".ser");
        }
        //initialise variables
        else {
            seriesLength = data.numAttributes()-1;

            if (numIntervals < 0){
                numIntervals = numIntervalsFinder.apply(seriesLength);
            }

            if (minIntervalLength < 0){
                minIntervalLength = minIntervalLengthFinder.apply(seriesLength);

                if (minIntervalLength < 3){
                    minIntervalLength = 3;
                }

                if (seriesLength <= minIntervalLength){
                    minIntervalLength = seriesLength/2;
                }
            }

            if (maxIntervalLength < 0){
                maxIntervalLength = maxIntervalLengthFinder.apply(seriesLength);
            }

            c22 = new Catch22();
            c22.setOutlierNormalise(outlierNorm);

            if (!useSummaryStats){
                numAttributes = 22;
            }

            startNumAttributes = numAttributes;
            subsampleAtts = new ArrayList();

            if (attSubsampleSize < numAttributes) {
                numAttributes = attSubsampleSize;
            }

            //Set up for Bagging if required
            if(bagging){
                inBag=new boolean[numClassifiers][data.numInstances()];

                if (getEstimateOwnPerformance()){
                    trainDistributions = new double[data.numInstances()][data.numClasses()];
                    oobCounts=new int[data.numInstances()];
                }
            }

            //cancel loop using time instead of number built.
            if (trainTimeContract){
                numClassifiers = -1;
                trees = new ArrayList<>();
                intervals = new ArrayList<>();
            }
            else{
                trees = new ArrayList<>(numClassifiers);
                intervals = new ArrayList<>(numClassifiers);
            }
        }

        //result can potentially be added as a field for checkpointing

        //Set up instances size and format.
        ArrayList<Attribute> atts=new ArrayList<>();
        String name;
        for(int j=0;j<numIntervals*numAttributes;j++){
            name = "F"+j;
            atts.add(new Attribute(name));
        }
        //Get the class values as an array list
        Attribute target =data.attribute(data.classIndex());
        ArrayList<String> vals=new ArrayList<>(target.numValues());
        for(int j=0;j<target.numValues();j++)
            vals.add(target.value(j));
        atts.add(new Attribute(data.attribute(data.classIndex()).name(),vals));
        //create blank instances with the correct class value
        Instances result = new Instances("Tree",atts,data.numInstances());
        result.setClassIndex(result.numAttributes()-1);
        for(int i=0;i<data.numInstances();i++){
            DenseInstance in=new DenseInstance(result.numAttributes());
            in.setValue(result.numAttributes()-1,data.instance(i).classValue());
            result.add(in);
        }

        testHolder = new Instances(result,0);
        DenseInstance in=new DenseInstance(result.numAttributes());
        testHolder.add(in);

        if(base instanceof RandomTree){
            ((RandomTree)base).setKValue(result.numAttributes() - 1);
        }

        /** For each base classifier 
         *      generate random intervals
         *      do the transfrorms
         *      build the classifier
         * */
        while((System.nanoTime()-trainResults.getBuildTime()- checkpointTimeDiff < contractTime
                || trees.size() < numClassifiers) && trees.size() < maxClassifiers){

            int i = trees.size();

            //1. Select random intervals for tree i

            int[][] interval =new int[numIntervals][2];  //Start and end

            for (int j = 0; j < numIntervals; j++) {
                interval[j][0] = rand.nextInt(seriesLength - minIntervalLength); //Start point

                int range = seriesLength - interval[j][0] > maxIntervalLength
                        ? maxIntervalLength : seriesLength - interval[j][0];
                int length = rand.nextInt(range);//Min length 3

                if (length < minIntervalLength)
                    length = minIntervalLength;
                interval[j][1] = interval[j][0] + length;
            }

            //If bagging find instances with replacement

            int[] instInclusions = new int[data.numInstances()];
            if (bagging){
                for (int n = 0; n < data.numInstances(); n++){
                    instInclusions[rand.nextInt(data.numInstances())]++;
                }

                for (int n = 0; n < data.numInstances(); n++){
                    if (instInclusions[n] > 0){
                        inBag[i][n] = true;
                    }
                }
            }

            //find attributes to subsample

            subsampleAtts.add(new ArrayList());
            for (int n = 0; n < startNumAttributes; n++){
                subsampleAtts.get(i).add(n);
            }

            while (subsampleAtts.get(i).size() > numAttributes){
                subsampleAtts.get(i).remove(rand.nextInt(subsampleAtts.get(i).size()));
            }

            //For bagging
            int instIdx = 0;
            int lastIdx = -1;

            //2. Generate and store attributes
            for(int k=0;k<data.numInstances();k++){
                //For each instance
                double[] series;

                if (bagging) {
                    boolean sameInst = false;

                    while (true) {
                        if (instInclusions[instIdx] == 0) {
                            instIdx++;
                        } else {
                            series = data.instance(instIdx).toDoubleArray();
                            instInclusions[instIdx]--;

                            if (instIdx == lastIdx) {
                                result.set(k, new DenseInstance(result.instance(k - 1)));
                                sameInst = true;
                            } else {
                                lastIdx = instIdx;
                            }

                            break;
                        }
                    }

                    if (sameInst) continue;

                    result.instance(k).setValue(result.numAttributes()-1,data.instance(instIdx).classValue());
                }
                else{
                    series = data.instance(k).toDoubleArray();
                }

                for(int j=0;j<numIntervals;j++){
                    //extract the interval

                    FeatureSet f= new FeatureSet();
                    double[] intervalArray = Arrays.copyOfRange(series, interval[j][0], interval[j][1] + 1);

                    //process features

                    for (int g = 0; g < subsampleAtts.get(i).size(); g++){
                        if (subsampleAtts.get(i).get(g) < 22) {
                            result.instance(k).setValue(j * numAttributes + g,
                                    c22.getSummaryStatByIndex(subsampleAtts.get(i).get(g), j, intervalArray));
                        }
                        else{
                            if (!f.calculatedFeatures) {
                                f.setFeatures(series, interval[j][0], interval[j][1]);
                            }

                            switch(subsampleAtts.get(i).get(g)) {
                                case 22:
                                    result.instance(k).setValue(j * numAttributes + g, f.mean);
                                    break;
                                case 23:
                                    result.instance(k).setValue(j * numAttributes + g, f.stDev);
                                    break;
                                case 24:
                                    result.instance(k).setValue(j * numAttributes + g, f.slope);
                                    break;
                                default:
                                    throw new Exception("att subsample basic features broke");
                            }
                        }
                    }
                }
            }

            if (bagging){
                result.randomize(rand);
            }

            //3. Create and build tree using all the features. Feature selection
            Classifier tree =AbstractClassifier.makeCopy(base);
            if(seedClassifier && tree instanceof Randomizable)
                ((Randomizable)tree).setSeed(seed*(i+1));

            tree.buildClassifier(result);

            if(bagging && getEstimateOwnPerformance()){
                long t1 = System.nanoTime();

                for(int n=0;n<data.numInstances();n++){
                    if(inBag[i][n])
                        continue;

                    double[] series = data.instance(n).toDoubleArray();
                    for(int j=0;j<numIntervals;j++) {

                        FeatureSet f = new FeatureSet();
                        double[] intervalArray = Arrays.copyOfRange(series, interval[j][0], interval[j][1] + 1);

                        for (int g = 0; g < subsampleAtts.get(i).size(); g++){
                            if (subsampleAtts.get(i).get(g) < 22) {
                                testHolder.instance(0).setValue(j * numAttributes + g,
                                        c22.getSummaryStatByIndex(subsampleAtts.get(i).get(g), j, intervalArray));
                            }
                            else{
                                if (!f.calculatedFeatures) {
                                    f.setFeatures(series, interval[j][0], interval[j][1]);
                                }
                                switch(subsampleAtts.get(i).get(g)) {
                                    case 22:
                                        testHolder.instance(0).setValue(j * numAttributes + g, f.mean);
                                        break;
                                    case 23:
                                        testHolder.instance(0).setValue(j * numAttributes + g, f.stDev);
                                        break;
                                    case 24:
                                        testHolder.instance(0).setValue(j * numAttributes + g, f.slope);
                                        break;
                                    default:
                                        throw new Exception("att subsample basic features broke");
                                }
                            }
                        }
                    }

                    double[] newProbs = tree.distributionForInstance(testHolder.instance(0));
                    oobCounts[n]++;
                    for(int k=0;k<newProbs.length;k++)
                        trainDistributions[n][k]+=newProbs[k];
                }

                trainResults.setErrorEstimateTime(trainResults.getErrorEstimateTime() + (System.nanoTime()-t1));
            }

            trees.add(tree);
            intervals.add(interval);

            if (checkpoint){
                checkpoint();
            }
        }

        trainResults.setBuildTime(System.nanoTime() - trainResults.getBuildTime() - checkpointTimeDiff
                - trainResults.getErrorEstimateTime());

/** Estimate accuracy stage: Three scenarios
 * 1. If we bagged the full build (bagging ==true), we estimate using the full build OOB
 *  If we built on all data (bagging ==false) we estimate either
 *  2. with a 10xCV or (if
 *  3. Build a bagged model simply to get the estimate.
 */
        if(getEstimateOwnPerformance()){
            long t1 = System.nanoTime();

            if(bagging){
                // Use bag data. Normalise probs
                double[] preds=new double[data.numInstances()];
                double[] actuals=new double[data.numInstances()];
                long[] predTimes=new long[data.numInstances()];//Dummy variable, need something
                for(int j=0;j<data.numInstances();j++){
                    long predTime = System.nanoTime();
                    for(int k=0;k<trainDistributions[j].length;k++)
                        trainDistributions[j][k]/=oobCounts[j];
                    preds[j]=utilities.GenericTools.indexOfMax(trainDistributions[j]);
                    actuals[j]=data.instance(j).classValue();
                    predTimes[j]=System.nanoTime()-predTime;
                }
                trainResults.addAllPredictions(actuals,preds, trainDistributions, predTimes, null);
                trainResults.setTimeUnit(TimeUnit.NANOSECONDS);
                trainResults.setClassifierName("TSFBagging");
                trainResults.setDatasetName(data.relationName());
                trainResults.setSplit("train");
                trainResults.setFoldID(seed);
                trainResults.setParas(getParameters());
                trainResults.finaliseResults(actuals);
            }
//Either do a CV, or bag and get the estimates 
            else if(estimator== EstimatorMethod.CV){
                /** Defaults to 10 or numInstances, whichever is smaller. 
                 * Interface TrainAccuracyEstimate
                 * Could this be handled better? */
                int numFolds=setNumberOfFolds(data);
                CrossValidationEvaluator cv = new CrossValidationEvaluator();
                if (seedClassifier)
                    cv.setSeed(seed*5);
                cv.setNumFolds(numFolds);
                C22IF tsf=new C22IF();
                tsf.copyParameters(this);
                if (seedClassifier)
                    tsf.setSeed(seed*100);
                tsf.setEstimateOwnPerformance(false);
                trainResults=cv.evaluate(tsf,data);
                trainResults.setClassifierName("TSFCV");
                trainResults.setParas(getParameters());

            }
            else if(estimator== EstimatorMethod.OOB){
                /** Build a single new TSF using Bagging, and extract the estimate from this
                 */
                C22IF tsf=new C22IF();
                tsf.copyParameters(this);
                tsf.setSeed(seed);
                tsf.setEstimateOwnPerformance(true);
                tsf.bagging=true;
                tsf.buildClassifier(data);
                trainResults=tsf.trainResults;
                trainResults.setClassifierName("TSFOOB");
                trainResults.setParas(getParameters());
            }

            trainResults.setErrorEstimateTime(trainResults.getErrorEstimateTime() + (System.nanoTime()-t1));
            trainResults.setBuildPlusEstimateTime(trainResults.getBuildTime() + trainResults.getErrorEstimateTime());
        }
    }

    private void copyParameters(C22IF other){
        this.numClassifiers = other.numClassifiers;
        this.attSubsampleSize = other.attSubsampleSize;
        this.outlierNorm = other.outlierNorm;
        this.useSummaryStats = other.useSummaryStats;
        this.numIntervals = other.numIntervals;
        this.numIntervalsFinder = other.numIntervalsFinder;
        this.minIntervalLength = other.minIntervalLength;
        this.minIntervalLengthFinder = other.minIntervalLengthFinder;
        this.maxIntervalLength = other.maxIntervalLength ;
        this.maxIntervalLengthFinder = other.maxIntervalLengthFinder;
        this.base = other.base;
        this.bagging = other.bagging;
        this.trainTimeContract = other.trainTimeContract;
        this.contractTime = other.contractTime;
    }

    /**
     * @param ins to classifier
     * @return array of doubles: probability of each class
     * @throws Exception
     */
    @Override
    public double[] distributionForInstance(Instance ins) throws Exception {
        double[] d=new double[ins.numClasses()];

        Catch22 c22 = new Catch22();
        c22.setOutlierNormalise(outlierNorm);

        //Build transformed instance
        double[] series=ins.toDoubleArray();
        for(int i=0;i<trees.size();i++){
            for(int j=0;j<numIntervals;j++){

                FeatureSet f = new FeatureSet();
                double[] intervalArray = Arrays.copyOfRange(series, intervals.get(i)[j][0],
                        intervals.get(i)[j][1]+1);

                for (int g = 0; g < subsampleAtts.get(i).size(); g++){
                    if (subsampleAtts.get(i).get(g) < 22) {
                        testHolder.instance(0).setValue(j * numAttributes + g,
                                c22.getSummaryStatByIndex(subsampleAtts.get(i).get(g), j, intervalArray));
                    }
                    else{
                        if (!f.calculatedFeatures) {
                            f.setFeatures(series, intervals.get(i)[j][0], intervals.get(i)[j][1]);
                        }
                        switch(subsampleAtts.get(i).get(g)) {
                            case 22:
                                testHolder.instance(0).setValue(j * numAttributes + g, f.mean);
                                break;
                            case 23:
                                testHolder.instance(0).setValue(j * numAttributes + g, f.stDev);
                                break;
                            case 24:
                                testHolder.instance(0).setValue(j * numAttributes + g, f.slope);
                                break;
                            default:
                                throw new Exception("att subsample basic features broke");
                        }
                    }
                }
            }

            if(voteEnsemble){
                int c=(int)trees.get(i).classifyInstance(testHolder.instance(0));
                d[c]++;
            }else{
                double[] temp=trees.get(i).distributionForInstance(testHolder.instance(0));
                for(int j=0;j<temp.length;j++)
                    d[j]+=temp[j];
            }
        }
        double sum=0;
        for(double x:d)
            sum+=x;
        for(int i=0;i<d.length;i++)
            d[i]=d[i]/sum;
        return d;
    }
    /**
     * @param ins
     * @return
     * @throws Exception
     */
    @Override
    public double classifyInstance(Instance ins) throws Exception {
        double[] d=distributionForInstance(ins);
        int max=0;
        for(int i=1;i<d.length;i++)
            if(d[i]>d[max])
                max=i;
        return (double)max;
    }

    @Override //Checkpointable
    public boolean setSavePath(String path) {
        boolean validPath=Checkpointable.super.setSavePath(path);
        if(validPath){
            checkpointPath = path;
            checkpoint = true;
        }
        return validPath;
    }

    @Override
    public void copyFromSerObject(Object obj) throws Exception {
        if(!(obj instanceof C22IF))
            throw new Exception("The SER file is not an instance of TSF");
        C22IF saved = ((C22IF)obj);
        System.out.println("Loading TSF" + seed + ".ser");

        try{
            numClassifiers = saved.numClassifiers;
            attSubsampleSize = saved.attSubsampleSize;
            numAttributes = saved.numAttributes;
            startNumAttributes = saved.startNumAttributes;
            subsampleAtts = saved.subsampleAtts;
            outlierNorm = saved.outlierNorm;
            useSummaryStats = saved.useSummaryStats;
            numIntervals = saved.numIntervals;
            //numIntervalsFinder = saved.numIntervalsFinder;
            minIntervalLength = saved.minIntervalLength;
            //minIntervalLengthFinder = saved.minIntervalLengthFinder;
            maxIntervalLength = saved.maxIntervalLength;
            //maxIntervalLengthFinder = saved.maxIntervalLengthFinder;
            trees = saved.trees;
            base = saved.base;
            intervals = saved.intervals;
            //testHolder = saved.testHolder;
            voteEnsemble = saved.voteEnsemble;
            bagging = saved.bagging;
            inBag = saved.inBag;
            oobCounts = saved.oobCounts;
            trainDistributions = saved.trainDistributions;
            estimator = saved.estimator;
            //checkpoint = saved.checkpoint;
            //checkpointPath = saved.checkpointPath
            checkpointTime = saved.checkpointTime;
            //checkpointTimeDiff = saved.checkpointTimeDiff;
            trainTimeContract = saved.trainTimeContract;
            contractTime = saved.contractTime;
            maxClassifiers = saved.maxClassifiers;
            seriesLength = saved.seriesLength;
            c22 = saved.c22;

            trainResults = saved.trainResults;
            rand = saved.rand;
            seedClassifier = saved.seedClassifier;
            seed = saved.seed;
            classifierName = saved.classifierName;

            checkpointTimeDiff = saved.checkpointTimeDiff + (System.nanoTime() - checkpointTime);
        }catch(Exception ex){
            System.out.println("Unable to assign variables when loading serialised file");
        }
    }

    @Override
    public void setTrainTimeLimit(TimeUnit time, long amount) {
        switch (time){
            case DAYS:
                contractTime = (long)(8.64e+13)*amount;
                break;
            case HOURS:
                contractTime = (long)(3.6e+12)*amount;
                break;
            case MINUTES:
                contractTime = (long)(6e+10)*amount;
                break;
            case SECONDS:
                contractTime = (long)(1e+9)*amount;
                break;
            case NANOSECONDS:
                contractTime = amount;
                break;
            default:
                throw new InvalidParameterException("Invalid time unit");
        }
        trainTimeContract = true;
    }

    private void checkpoint(){
        if(checkpointPath!=null){
            try{
                long t1 = System.nanoTime();
                File f = new File(checkpointPath);
                if(!f.isDirectory())
                    f.mkdirs();

                //time spent building so far.
                checkpointTime = System.nanoTime();

                //save this, classifiers and train data not included
                saveToFile(checkpointPath + "C22IF" + seed + "temp.ser");

                File file = new File(checkpointPath + "C22IF" + seed + "temp.ser");
                File file2 = new File(checkpointPath + "C22IF" + seed + ".ser");
                file2.delete();
                file.renameTo(file2);

                checkpointTimeDiff += System.nanoTime()-t1;
            }
            catch(Exception e){
                e.printStackTrace();
                System.out.println("Serialisation to "+checkpointPath+"C22IF" + seed + ".ser FAILED");
            }
        }
    }

    //Nested class to store three simple summary features used to construct train data
    public static class FeatureSet{
        double mean;
        double stDev;
        double slope;
        boolean calculatedFeatures = false;

        public void setFeatures(double[] data, int start, int end){
            double sumX=0,sumYY=0;
            double sumY=0,sumXY=0,sumXX=0;
            int length=end-start+1;
            for(int i=start;i<=end;i++){
                sumY+=data[i];
                sumYY+=data[i]*data[i];
                sumX+=(i-start);
                sumXX+=(i-start)*(i-start);
                sumXY+=data[i]*(i-start);
            }
            mean=sumY/length;
            stDev=sumYY-(sumY*sumY)/length;
            slope=(sumXY-(sumX*sumY)/length);
            double denom=sumXX-(sumX*sumX)/length;
            if(denom!=0)
                slope/=denom;
            else
                slope=0;
            stDev/=length;
            if(stDev==0)    //Flat line
                slope=0;
//            else //Why not doing this? Because not needed?
//                stDev=Math.sqrt(stDev);
            if(slope==0)
                stDev=0;

            calculatedFeatures = true;
        }
    }

    public double[][] temporalImportanceCurve() throws Exception{
        if (!(base instanceof TimeSeriesTree))
            throw new Exception("Temporal importance curve only available for time series tree");

        double[][] curves = new double[startNumAttributes][seriesLength];

        for (int i = 0; i < trees.size(); i++){
            TimeSeriesTree tree = (TimeSeriesTree)trees.get(i);
            ArrayList<Double>[] sg = tree.getTreeSplitsGain();

            for (int n = 0; n < sg[0].size(); n++){
                double split = sg[0].get(n);
                double gain = sg[1].get(n);
                int interval = (int)(split/numAttributes);

                int att = (int)(split%numAttributes);
                att = subsampleAtts.get(i).get(att);

                for (int j = intervals.get(i)[interval][0]; j <= intervals.get(i)[interval][1]; j++){
                    curves[att][j] += gain;
                }
            }
        }

        return curves;
    }

    public static void main(String[] arg) throws Exception{
        String dataLocation="Z:\\ArchiveData\\Univariate_arff\\";
        String problem="ItalyPowerDemand";
        Instances train=DatasetLoading.loadDataNullable(dataLocation+problem+"\\"+problem+"_TRAIN");
        Instances test=DatasetLoading.loadDataNullable(dataLocation+problem+"\\"+problem+"_TEST");
        C22IF c = new C22IF();
        c.setSeed(0);
        c.bagging = true;
        //c.setNumTrees(250);
        //c.setNumIntervalsFinder((numAtts) -> (int)(Math.pow(Math.sqrt(numAtts), 0.85)));
        //c.setEstimateOwnPerformance(true);
        double a;
        long t1 = System.nanoTime();
        c.buildClassifier(train);
        System.out.println("Train time="+(System.nanoTime()-t1)*1e-9);
        System.out.println("build ok: original atts = "+(train.numAttributes()-1)+" new atts = "
                +(c.testHolder.numAttributes()-1)+" num trees = "+c.trees.size()+" num intervals = "+c.numIntervals);
        System.out.println("recorded times: train time = "+(c.trainResults.getBuildTime()*1e-9)+" estimate time = "
                +(c.trainResults.getErrorEstimateTime()*1e-9)
                +" both = "+(c.trainResults.getBuildPlusEstimateTime()*1e-9));
        a=ClassifierTools.accuracy(test, c);
        System.out.println("Test Accuracy = "+a);
        System.out.println("Train Accuracy = "+c.trainResults.getAcc());
    }
}
  