package tsml.classifiers.distance_based.knn;

import evaluation.storage.ClassifierResults;
import experiments.data.DatasetLoading;
import tsml.classifiers.TrainTimeContractable;
import tsml.classifiers.distance_based.distances.AbstractDistanceMeasure;
import tsml.classifiers.distance_based.knn.neighbour_iteration.RandomNeighbourIteratorBuilder;
import tsml.filters.IndexFilter;
import utilities.*;
import utilities.cache.Cache;
import utilities.cache.SymmetricCache;
import utilities.params.ParamHandler;
import utilities.params.ParamSet;
import weka.core.DistanceFunction;
import weka.core.Instance;
import weka.core.Instances;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class KnnLoocv
    extends Knn implements TrainTimeContractable {

    private static final long serialVersionUID = 0;
    public static final String NEIGHBOUR_LIMIT_FLAG = "n";
    public static final String NEIGHBOUR_ITERATION_STRATEGY_FLAG = "s";
    protected transient long trainTimeLimitNanos = -1;
    protected List<NeighbourSearcher> searchers;
    protected long longestNeighbourEvalTimeInNanos;
    protected int neighbourLimit = -1;
    protected int neighbourCount;
    protected int comparisonCount;
    protected StopWatch trainEstimateTimer = new StopWatch();
    protected Cache<Instance, Instance, Double> cache;
    protected NeighbourSearcher leftOutSearcher = null;
    protected Iterator<NeighbourSearcher> leftOutSearcherIterator;
    protected Iterator<NeighbourSearcher> cvSearcherIterator;
    protected NeighbourIteratorBuilder neighbourIteratorBuilder = new RandomNeighbourIteratorBuilder(this);
    protected NeighbourIteratorBuilder cvSearcherIteratorBuilder = new RandomNeighbourIteratorBuilder(this);
    protected boolean customCache = false;
    private boolean rebuild = true; // shadows super

    public KnnLoocv() {
        setAbleToEstimateOwnPerformance(true);
    }

    public KnnLoocv(DistanceFunction df) {
        super(df);
        setAbleToEstimateOwnPerformance(true);
    }

    public StopWatch getTrainEstimateTimer() {
        return trainEstimateTimer;
    }

    public int getNeighbourCount() {
        return neighbourCount;
    }

    public int getComparisonCount() {
        return comparisonCount;
    }

    @Override
    public void setTrainTimeLimit(long nanos) {
        trainTimeLimitNanos = nanos;
    }

    public List<NeighbourSearcher> getSearchers() {
        return searchers;
    }

    public boolean hasNextNeighbourSearch() {
        return leftOutSearcherIterator.hasNext() || cvSearcherIterator.hasNext();
    }

    public boolean hasNextNeighbourLimit() {
        return (neighbourCount < neighbourLimit || neighbourLimit < 0);
    }

    public boolean hasNextNeighbour() {
        return hasNextNeighbourSearch() && hasNextNeighbourLimit();
    }

    public boolean hasNextBuildTick() throws Exception {
        return estimateOwnPerformance && hasNextNeighbour() && hasRemainingTrainTime();
    }

    public long predictNextTrainTimeNanos() {
        return longestNeighbourEvalTimeInNanos;
    }

    protected void nextBuildTick() throws Exception {
        regenerateTrainEstimate = true;
        final long timeStamp = System.nanoTime();
        if(leftOutSearcher == null) {
            leftOutSearcher = leftOutSearcherIterator.next();
            leftOutSearcherIterator.remove();
        }
        comparisonCount++;
        final NeighbourSearcher searcher = cvSearcherIterator.next();
        cvSearcherIterator.remove();
        final Instance instance = searcher.getInstance();
        final Instance leftOutInstance = leftOutSearcher.getInstance();
        if(!leftOutInstance.equals(instance)) {
            boolean seen;
            if(customCache) {
                seen = cache.contains(leftOutInstance, instance);
            } else {
                seen = cache.remove(leftOutInstance, instance);
            }
            if(seen) {
                // we've already seen this instance
                logger.info(() -> comparisonCount + ") " + "already seen i" + instance.hashCode() + " and i" + leftOutInstance.hashCode());
            } else {
                final long distanceMeasurementTimeStamp = System.nanoTime();
                Double distance = customCache ? cache.get(instance, leftOutInstance) : null;
                final long timeTakenInNanos = System.nanoTime() - distanceMeasurementTimeStamp;
                if(distance == null) {
                    distance = searcher.add(leftOutInstance);
                } else {
                    searcher.add(leftOutInstance, distance, timeTakenInNanos);
                }
                leftOutSearcher.add(instance, distance, 0); // we get this for free!
                cache.put(instance, leftOutInstance, distance);
                final Double finalDistance = distance;
                logger.info(() -> comparisonCount + ") i" + instance.hashCode() + " and i" + leftOutInstance.hashCode() +
                                 ": " + finalDistance);
            }
        } else {
            logger.info(() -> comparisonCount + ") i" + instance.hashCode() + " and i" + leftOutInstance.hashCode() +
                            ": left out");
        }
        if(!cvSearcherIterator.hasNext()) {
            if(leftOutSearcherIterator.hasNext()) {
                cvSearcherIterator = cvSearcherIteratorBuilder.build();
                if(logger.isLoggable(Level.WARNING)) {
                    logger.info("---- end of batch ----");
                    if(!cvSearcherIterator.hasNext()) {
                        throw new IllegalStateException("this shouldn't happen!");
                    }
                    if(!leftOutSearcherIterator.hasNext()) {
                        throw new IllegalStateException("this shouldn't happen!");
                    }
                }
            }
            leftOutSearcher = null;
            neighbourCount++;
        }
        longestNeighbourEvalTimeInNanos = System.nanoTime() - timeStamp;
    }

    public NeighbourIteratorBuilder getNeighbourIteratorBuilder() {
        return neighbourIteratorBuilder;
    }

    public void setNeighbourIteratorBuilder(final NeighbourIteratorBuilder neighbourIteratorBuilder) {
        this.neighbourIteratorBuilder = neighbourIteratorBuilder;
    }

    public boolean hasNeighbourLimit() {
        return neighbourLimit >= 0;
    }

    public interface NeighbourIteratorBuilder extends Serializable {
        Iterator<NeighbourSearcher> build();
    }

    @Override public ParamSet getParams() {
        return super.getParams()
                    .add(NEIGHBOUR_ITERATION_STRATEGY_FLAG, neighbourIteratorBuilder)
                    .add(NEIGHBOUR_LIMIT_FLAG, neighbourLimit)
                    .addAll(TrainTimeContractable.super.getParams());
    }

    @Override public void setParams(final ParamSet params) {
        super.setParams(params);
        ParamHandler.setParam(params, NEIGHBOUR_LIMIT_FLAG, this::setNeighbourLimit, Integer.class);
        ParamHandler.setParam(params, NEIGHBOUR_ITERATION_STRATEGY_FLAG, this::setNeighbourIteratorBuilder,
                              NeighbourIteratorBuilder.class);
        TrainTimeContractable.super.setParams(params);
    }

    public boolean loadFromCheckpoint() {
        trainTimer.suspend();
        trainEstimateTimer.suspend();
        memoryWatcher.suspend();
        boolean result = super.loadFromCheckpoint();
        memoryWatcher.unsuspend();
        trainEstimateTimer.unsuspend();
        trainTimer.unsuspend();
        return result;
    }

    @Override
    public void setRetrain(boolean rebuild) {
        this.rebuild = rebuild;
        super.setRetrain(rebuild);
    }

    @Override public void buildClassifier(final Instances trainData) throws Exception {
        memoryWatcher.enable();
        trainEstimateTimer.checkDisabled();
        trainTimer.enable();
        boolean skip = isSkipFinalCheckpoint();
        setSkipFinalCheckpoint(true);
        // must disable train timer and memory watcher as super enables them at start of build
        trainTimer.disable();
        memoryWatcher.disable();
        super.buildClassifier(trainData);
        setSkipFinalCheckpoint(skip);
        built = false;
        memoryWatcher.enableAnyway();
        trainEstimateTimer.checkDisabled();
        trainTimer.enableAnyway();
        if(rebuild) {
            trainTimer.disableAnyway();
            memoryWatcher.enableAnyway();
            trainEstimateTimer.resetAndEnable();
            rebuild = false;
            if(getEstimateOwnPerformance()) {
                if(isCheckpointSavingEnabled()) { // was needed for caching
                    IndexFilter.hashifyInstances(trainData);
                }
                // build a progressive leave-one-out-cross-validation
                searchers = new ArrayList<>(trainData.size());
                // build a neighbour searcher for every train instance
                for(int i = 0; i < trainData.size(); i++) {
                    final NeighbourSearcher searcher = new NeighbourSearcher(trainData.get(i), rand);
                    searchers.add(i, searcher);
                }
                if(distanceFunction instanceof AbstractDistanceMeasure) {
                    if(((AbstractDistanceMeasure) distanceFunction).isSymmetric()) {
                        cache = new SymmetricCache<>();
                    } else {
                        cache = new Cache<>();
                    }
                }
                leftOutSearcherIterator = neighbourIteratorBuilder.build();
                regenerateTrainEstimate = true; // build the first train estimate irrelevant of any progress made
                cvSearcherIterator = cvSearcherIteratorBuilder.build();
                if(logger.isLoggable(Level.WARNING)) {
                    if(!leftOutSearcherIterator.hasNext()) {
                        throw new IllegalStateException("hasNext false");
                    }
                    if(!cvSearcherIterator.hasNext()) {
                        throw new IllegalStateException("this shouldn't happen!");
                    }
                }
                longestNeighbourEvalTimeInNanos = -1;
                leftOutSearcher = null;
                cvSearcherIterator = cvSearcherIteratorBuilder.build();
                neighbourCount = 0;
                comparisonCount = 0;
            }
        }
        trainTimer.disableAnyway();
        trainEstimateTimer.enableAnyway();
        while(hasNextBuildTick()) {
            nextBuildTick();
            saveToCheckpoint();
        }
        trainTimer.checkDisabled();
        if(regenerateTrainEstimate) {
            if(logger.isLoggable(Level.WARNING)
                && !hasTrainTimeLimit()
                && ((hasNeighbourLimit() && neighbourCount < neighbourLimit) ||
                        (!hasNeighbourLimit() && neighbourCount < trainData.size()))) {
                throw new IllegalStateException("not fully built");
            }
            // populate train results
            trainResults = new ClassifierResults();
            for(final NeighbourSearcher searcher : searchers) {
                final double[] distribution = searcher.predict();
                final int prediction = Utilities.argMax(distribution, rand);
                final long time = searcher.getTimeNanos();
                final double trueClassValue = searcher.getInstance().classValue();
                trainResults.addPrediction(trueClassValue, distribution, prediction, time, null);
            }
        }
        trainEstimateTimer.disable();
        memoryWatcher.cleanup();
        memoryWatcher.disable();
        if(regenerateTrainEstimate) {
            regenerateTrainEstimate = false;
            trainResults.setDetails(this, trainData);
            trainResults.setTimeUnit(TimeUnit.NANOSECONDS);
            trainResults.setBuildTime(trainEstimateTimer.getTimeNanos());
            trainResults.setBuildPlusEstimateTime(trainEstimateTimer.getTimeNanos() + trainTimer.getTimeNanos());
        }
        built = true;
        saveToCheckpoint();
    }

    public long getTrainTimeNanos() {
        return trainEstimateTimer.getTimeNanos() + trainTimer.getTimeNanos();
    }

    public long getTrainTimeLimitNanos() {
        return trainTimeLimitNanos;
    }

    @Override
    public void setTrainTimeLimitNanos(final long trainTimeLimit) {
        this.trainTimeLimitNanos = trainTimeLimit;
    }

    public int getNeighbourLimit() {
        return neighbourLimit;
    }

    public void setNeighbourLimit(final int neighbourLimit) {
        this.neighbourLimit = neighbourLimit;
    }

    public Cache<Instance, Instance, Double> getCache() {
        return cache;
    }

    public void setCache(final Cache<Instance, Instance, Double> cache) {
        this.cache = cache;
        customCache = cache != null;
    }

    public void setDefaultCache() {
        setCache(null);
    }

    public NeighbourIteratorBuilder getCvSearcherIteratorBuilder() {
        return cvSearcherIteratorBuilder;
    }

    public void setCvSearcherIteratorBuilder(final NeighbourIteratorBuilder cvSearcherIteratorBuilder) {
        this.cvSearcherIteratorBuilder = cvSearcherIteratorBuilder;
    }

    public static void main(String[] args) throws Exception {
        int seed = 0;
        Instances[] data = DatasetLoading.sampleGunPoint(seed);
        KnnLoocv classifier = new KnnLoocv();
        classifier.setSeed(seed); // set seed
        classifier.setEstimateOwnPerformance(true);
        ClassifierResults results = ClassifierTools.trainAndTest(data, classifier);
        results.setDetails(classifier, data[1]);
        ClassifierResults trainResults = classifier.getTrainResults();
        trainResults.setDetails(classifier, data[0]);
        System.out.println(trainResults.writeSummaryResultsToString());
        System.out.println(results.writeSummaryResultsToString());
    }
}

