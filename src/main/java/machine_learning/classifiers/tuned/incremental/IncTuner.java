package machine_learning.classifiers.tuned.incremental;

import com.google.common.primitives.Doubles;
import evaluation.storage.ClassifierResults;
import tsml.classifiers.*;
import utilities.*;
import utilities.params.ParamHandler;
import utilities.params.ParamSet;
import utilities.serialisation.SerConsumer;
import weka.core.Instance;
import weka.core.Instances;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static utilities.collections.Utils.replace;

public class IncTuner extends EnhancedAbstractClassifier implements TrainTimeContractable, GcMemoryWatchable,
                                                                    StopWatchTrainTimeable,
                                                                    Checkpointable, Parallelisable, TestSeedable {

    /*
        how do we do tuning?

        general idea:
            we have benchmarks which contain a classifier and a score (we don't care whether that's acc or
            auroc or anything, as long as it's comparable).

            we iterate over benchmarks, each iteration returning a set of benchmarks which have been adjusted. we must
            therefore have an id field in benchmarks to differentiate them and recognise when we've already seen them.

            when there are no more iterations then we've collected all benchmarks possible. we must next whittle them
            down to a subset / filter them (i.e. choose the best 10, say).

            the best benchmarks are then ensembled together to represent a classifier. if this is only 1 benchmark then
            obviously ensembling has no effect, we're just wrapping that benchmark at that point.

        considerations:
            every benchmark id represents a new benchmark. if a benchmark is improved then either the benchmark object
            must be kept the same or the id of the previous benchmark copied over to the next.

            contracting becomes complex if we implement it here, therefore it is best left to the iteration strategy to
            handle contracting. we can have a naive version which simply iterates over benchmarks until hitting the
            contract.

            it's a similar deal with checkpointing. the classifier in each benchmark may checkpoint themselves. we don't
            want this behaviour as this may cause too frequent or infrequent checkpointing. therefore, we must do the
            checkpointing here manually.

            we may also be running this in parallel, therefore we need to detect that and run a single benchmark only.
            that way this can be parallelised. we cannot operate in parallel with a contract though, can we? perhaps
            we could divide the contract by how many benchmarks we receive? that would require knowing the number of
            benchmarks to come, which could only be done by draining the iterator fully, which may not work with certain
            iteration patterns. we would have to require the iterator to know this and only provide however many
            benchmarks we're looking at without doing any improvement or anything.

        constraints:
            nada one.

     */

    public IncTuner() {
        super(true);
    }

    public boolean isLogBenchmarks() {
        return logBenchmarks;
    }

    public void setLogBenchmarks(boolean logBenchmarks) {
        this.logBenchmarks = logBenchmarks;
    }

    public boolean isDebugBenchmarks() {
        return debugBenchmarks;
    }

    public void setDebugBenchmarks(boolean debugBenchmarks) {
        this.debugBenchmarks = debugBenchmarks;
    }

    protected Agent agent = new Agent() {
        @Override
        public Set<EnhancedAbstractClassifier> findFinalClassifiers() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasNext() {
            throw new UnsupportedOperationException();
        }

        @Override public EnhancedAbstractClassifier next() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean feedback(EnhancedAbstractClassifier classifier) {
            throw new UnsupportedOperationException();
        }
    };
    protected transient Set<EnhancedAbstractClassifier> benchmarks = new HashSet<>();
    protected Ensembler ensembler = Ensembler.byScore(benchmark -> benchmark.getTrainResults().getAcc());
    protected List<Double> ensembleWeights = new ArrayList<>();
    protected SerConsumer<Instances> trainSetupFunction = instances -> {};
    protected MemoryWatcher memoryWatcher = new MemoryWatcher();
    protected StopWatch trainTimer = new StopWatch();
    protected StopWatch trainEstimateTimer = new StopWatch();
    public static final String BENCHMARK_ITERATOR_FLAG = "b";
    public static final String TRAIN_SETUP_FUNCTION_FLAG = "i";
    private transient boolean debugBenchmarks = false;
    private transient boolean logBenchmarks = false;
    private transient Instances trainData;
    private transient boolean hasSkippedEvaluation = false;
    private Set<String> classifierNames;
    private transient boolean yielded = false;
    protected int testSeed = 0;
    protected Random testRand = new Random(0);

    @Override public void setTestSeed(final int testSeed) {
        this.testSeed = testSeed;
    }

    @Override public int getTestSeed() {
        return testSeed;
    }

    // start boiler plate ----------------------------------------------------------------------------------------------

    private boolean rebuild = true; // shadows super
    private boolean built = false;
    protected transient long trainTimeLimitNanos = -1;
    private static final long serialVersionUID = 0;
    protected transient long minCheckpointIntervalNanos = Checkpointable.DEFAULT_MIN_CHECKPOINT_INTERVAL;
    protected transient long lastCheckpointTimeStamp = 0;
    protected transient String savePath = null;
    protected transient String loadPath = null;
    protected transient boolean skipFinalCheckpoint = false;
    protected static final String DONE_FILE_EXTENSION = "done";

    @Override
    public boolean isSkipFinalCheckpoint() {
        return skipFinalCheckpoint;
    }

    @Override
    public void setSkipFinalCheckpoint(boolean skipFinalCheckpoint) {
        this.skipFinalCheckpoint = skipFinalCheckpoint;
    }

    @Override
    public String getSavePath() {
        return savePath;
    }

    @Override
    public boolean setSavePath(String path) {
        boolean result = Checkpointable.super.setSavePath(path);
        if(result) {
            savePath = StrUtils.asDirPath(path);
        } else {
            savePath = null;
        }
        return result;
    }

    @Override public String getLoadPath() {
        return loadPath;
    }

    @Override public boolean setLoadPath(final String path) {
        boolean result = Checkpointable.super.setLoadPath(path);
        if(result) {
            loadPath = StrUtils.asDirPath(path);
        } else {
            loadPath = null;
        }
        return result;
    }

    public StopWatch getTrainTimer() {
        return trainTimer;
    }

    public Instances getTrainData() {
        return trainData;
    }

    public long getLastCheckpointTimeStamp() {
        return lastCheckpointTimeStamp;
    }

    public boolean saveToCheckpoint() throws Exception {
        return false;
    }

    public boolean loadFromCheckpoint() {
        return false;
    }

    public void setMinCheckpointIntervalNanos(final long nanos) {
        minCheckpointIntervalNanos = nanos;
    }

    public long getMinCheckpointIntervalNanos() {
        return minCheckpointIntervalNanos;
    }

    @Override public MemoryWatcher getMemoryWatcher() {
        return memoryWatcher;
    }

    @Override
    public void setRetrain(boolean rebuild) {
        this.rebuild = rebuild;
        super.setRetrain(rebuild);
    }

    @Override public void setLastCheckpointTimeStamp(final long lastCheckpointTimeStamp) {
        this.lastCheckpointTimeStamp = lastCheckpointTimeStamp;
    }

    public StopWatch getTrainEstimateTimer() {
        return trainEstimateTimer;
    }

    @Override public ParamSet getParams() {
        return TrainTimeContractable.super.getParams()
                                    .add(BENCHMARK_ITERATOR_FLAG, agent)
                                    .add(TRAIN_SETUP_FUNCTION_FLAG, trainSetupFunction);
    }

    @Override public void setParams(final ParamSet params) {
        TrainTimeContractable.super.setParams(params);
        ParamHandler.setParam(params, BENCHMARK_ITERATOR_FLAG, this::setAgent, Agent.class);
        ParamHandler.setParam(params, TRAIN_SETUP_FUNCTION_FLAG, this::setTrainSetupFunction, SerConsumer.class); //
        // todo
        // finish params
    }

    @Override public void setTrainTimeLimitNanos(final long nanos) {
        trainTimeLimitNanos = nanos;
    }

    @Override public long predictNextTrainTimeNanos() {
        return agent.predictNextTimeNanos();
    }

    @Override public boolean isBuilt() {
        return !agent.hasNext();
    }

    @Override public long getTrainTimeLimitNanos() {
        return trainTimeLimitNanos;
    }

    // end boiler plate ------------------------------------------------------------------------------------------------

    @Override public void buildClassifier(final Instances trainData) throws Exception {
        // setup parent
        super.buildClassifier(trainData);
        // enable resource monitors
        memoryWatcher.enableAnyway();
        trainEstimateTimer.checkDisabled();
        trainTimer.enableAnyway();
        // we're not built at the moment
        built = false;
        this.trainData = trainData;
        lastCheckpointTimeStamp = System.nanoTime();
        // if we're rebuilding
        if(rebuild) {
            // reset resource monitors
            trainTimer.resetAndEnable();
            memoryWatcher.resetAndEnable();
            trainSetupFunction.accept(trainData);
            // reset this switch so we don't reset again next time (unless someone calls the setter)
            rebuild = false;
            hasSkippedEvaluation = false;
            yielded = false;
            classifierNames = new HashSet<>();
        }
        // going into train estimate phase
        // while we've got more benchmarks to examine
        while(hasNextBuildTick()) {
            // get those benchmarks
            nextBuildTick();
        }
        // sanity check resource monitors (as the benchmark iterator *should* have been using them)
        trainEstimateTimer.checkDisabled();
        trainTimer.checkEnabled();
        memoryWatcher.checkEnabled();
        // check whether we've skipped any work due to parallelisation / locking
        if(hasSkippedEvaluation) {
            if(!isCheckpointSavingEnabled()) {
                throw new IllegalStateException("skipped evaluation but checkpointing not enabled");
            }
        } else {
            // if we haven't skipped any evaluation then we can have a go at finding all the done files and creating the
            // overall done file
            boolean waitingForOthers = false;
            for(String name : classifierNames) {
                if(classifierAlreadyFullyBuilt(name)) {
                    waitingForOthers = true;
                    break;
                }
            }
            if(!waitingForOthers) {
                // try and create the overall done file
                try (FileUtils.FileLock lock = new FileUtils.FileLock(savePath + "overall.done")) {
                    // then continue with regular bits
                    // find the final benchmarks
                    benchmarks = agent.findFinalClassifiers();
                    // sanity check and ensemble
                    if(benchmarks.isEmpty()) {
                        logger.info(() -> "no benchmarks collected");
                        ensembleWeights = new ArrayList<>();
                        throw new UnsupportedOperationException("todo implement random guess here?");
                    } else if(benchmarks.size() == 1) {
                        logger.info(() -> "single benchmarks collected");
                        ensembleWeights = new ArrayList<>(Collections.singletonList(1d));
                        trainResults = benchmarks.iterator().next().getTrainResults();
                    } else {
                        logger.info(() -> benchmarks.size() + " benchmarks collected");
                        ensembleWeights = ensembler.weightVotes(benchmarks);
                        throw new UnsupportedOperationException("todo apply ensemble weights to train results"); // todo
                    }
                    // cleanup
                    trainEstimateTimer.checkDisabled();
                    trainTimer.disable();
                    memoryWatcher.disable();
                    memoryWatcher.cleanup();
                    trainResults.setMemory(getMaxMemoryUsageInBytes());
                    trainResults.setBuildTime(trainTimer.getTimeNanos());
                    trainResults.setBuildPlusEstimateTime(getTrainTimeNanos());
                    trainResults.setTimeUnit(TimeUnit.NANOSECONDS);
                    trainResults.setFoldID(seed);
                    trainResults.setDetails(this, trainData);
                    boolean created = createDoneFile("overall");
                    if(!created) {
                        throw new IllegalStateException("could not create overall done file");
                    }
                    built = true;
                } catch (FileUtils.FileLock.LockException e) {
                    logger.info(() -> "cannot lock overall done file");
                    yielded = true;
                    // quit as another process is going to finish up the build
                }
            }
        }
        this.trainData = null;
        memoryWatcher.disableAnyway();
        trainEstimateTimer.disableAnyway();
        trainTimer.disableAnyway();
    }

    @Override
    public boolean isFullyTrained() {
        return built;
    }

    @Override
    public boolean isFinalModel() {
        return !hasSkippedEvaluation && !yielded;
    }

    protected boolean hasNextBuildTick() {
        return agent.hasNext() && hasRemainingTraining();
    }

    protected void suspendResourceMonitors() {
        trainTimer.suspend();
        trainEstimateTimer.suspend();
        memoryWatcher.suspend();
    }

    protected void unsuspendResourceMonitors() {
        trainTimer.unsuspend();
        trainEstimateTimer.unsuspend();
        memoryWatcher.unsuspend();
    }

    protected void nextBuildTick() throws Exception {
        // check whether we're exploring or exploiting
        final boolean isExplore = agent.isExploringOrExploiting();
        // get the next classifier
        EnhancedAbstractClassifier classifier = agent.next();
        boolean evaluate = true;
        // suspend the resource monitors while we're sorting out checkpointing bits
        suspendResourceMonitors();
        // find the save path for the classifier
        String classifierSavePath = null;
        // we may be running in distributed mode therefore must get a lock on the classifier
        // we'll do this by locking the checkpoint directory for the classifier
        FileUtils.FileLock lock = null;
        if(isCheckpointSavingEnabled()){
            // otherwise no done file, so let's try and lock the classifier's checkpoint dir to claim it
            classifierSavePath = buildClassifierSavePath(classifier);
            if(classifier instanceof Checkpointable) {
                ((Checkpointable) classifier).setSavePath(classifierSavePath);
            }
            lock = new FileUtils.FileLock(classifierSavePath);
            // if we're claimed the lock then we can evaluate the classifier
            evaluate = lock.isLocked();
            if(evaluate) {
                // we've claimed the lock
                // if classifier is already done don't evaluate it
                evaluate = !classifierAlreadyFullyBuilt(classifier.getClassifierName());
            }
            // add the classifier name to the set so we know which ones we've seen
            classifierNames.add(classifier.getClassifierName());
        }
        unsuspendResourceMonitors();
        // update whether we've ever skipped an evaluation
        hasSkippedEvaluation |= !evaluate;
        // if we managed to lock the file OR we're not checkpointing whatsoever OR the classifier is not already done
        if(evaluate) {
            // then evaluate the classifier
            if(isExplore) {
                // if we're exploring then load classifier from checkpoint (if enabled)
                classifier = loadClassifier(classifier);
                // and set meta fields
                classifier.setSeed(seed);
                classifier.setDebug(isDebugBenchmarks());
                if(isLogBenchmarks()) {
                    classifier.getLogger().setLevel(getLogger().getLevel());
                } else {
                    classifier.getLogger().setLevel(Level.SEVERE);
                }
                // setup checkpoint saving
                if(isCheckpointSavingEnabled() && classifier instanceof Checkpointable) {
                    ((Checkpointable) classifier).setMinCheckpointIntervalNanos(minCheckpointIntervalNanos);
                    ((Checkpointable) classifier).setSavePath(buildClassifierSavePath(classifier));
                }
            }
            StopWatch classifierTrainTimer = new StopWatch();
            MemoryWatcher classifierMemoryWatcher = new MemoryWatcher();
            // build the classifier
            EnhancedAbstractClassifier finalClassifier = classifier;
            logger.info(() -> "evaluating " + StrUtils.toOptionValue(finalClassifier));
            classifier.setEstimateOwnPerformance(true);
            if(classifier instanceof TrainTimeable) {
                // then we don't need to record train time as classifier does so internally
            } else {
                // otherwise we'll enable our train timer to record timings
                classifierTrainTimer.enable();
            }
            if(classifier instanceof MemoryWatchable) {
                // then we don't need to record memory usage as classifier does so internally
            } else {
                // otherwise we'll enable our memory watcher to record memory usage
                classifierMemoryWatcher.enable();
            }
            // build the classifier
            classifier.buildClassifier(trainData);
            // enable tracking of resources for tuning process
            classifierTrainTimer.disableAnyway();
            classifierMemoryWatcher.disableAnyway();
            this.trainEstimateTimer.checkDisabled();
            this.memoryWatcher.enableAnyway();
            this.trainTimer.enableAnyway();
            // set train info
            classifier.getTrainResults().setDetails(classifier, trainData);
            // add the resource usage onto our monitors
            if(classifier instanceof TrainTimeable) {
                // the classifier tracked its time internally
                this.trainTimer.add(((TrainTimeable) classifier).getTrainTimeNanos());
                this.trainEstimateTimer.add(((TrainTimeable) classifier).getTrainEstimateTimeNanos());
            } else {
                // we tracked the classifier's time
                trainTimer.add(classifierTrainTimer);
                // set train results info
                classifier.getTrainResults().setBuildTime(classifierTrainTimer.getTimeNanos());
            }
            if(classifier instanceof MemoryWatchable) {
                // the classifier tracked its own memory
                memoryWatcher.add((MemoryWatchable) classifier);
            } else {
                // we tracked the memory usage of the classifier
                memoryWatcher.add(classifierMemoryWatcher);
                // set train results info
                classifier.getTrainResults().setMemoryDetails(classifierMemoryWatcher);
            }
            // feed the built classifier back to the agent (which will decide what to do with it)
            boolean classifierFullyBuilt = !agent.feedback(classifier);
            suspendResourceMonitors();
            // if classifier is fully built OR we're dealing with a classifier which cannot checkpoint itself and the
            // checkpoint interval has elapsed
            if (classifierFullyBuilt ||
                    (hasCheckpointIntervalElapsed() && !(classifier instanceof Checkpointable))
                ) {
                // no more exploitations will be made to this classifier, therefore let's save to disk
                saveClassifier(classifier);
                lastCheckpointTimeStamp = System.nanoTime();
                // create a done file to indicate this classifier is complete (only useful for distributed mode)
                // the thinking here is we may be running in distributed mode (i.e. multiple threads / processes / pcs
                // distributed mode assumes there's a shared filesystem which we're writing checkpoints to
                // therefore we'll create a done file to indicate we've completed this classifier
                if(classifierFullyBuilt) {
                    if(!createDoneFile(classifier.getClassifierName())) {
                        throw new IllegalStateException("cannot create done file, this should never happen!");
                    }
                }
            }
            // if we've been running in distributed mode then we need to unlock the lock file
            if(lock != null) {
                lock.unlock();
            }
            unsuspendResourceMonitors();
        } else {
            // couldn't lock file, so we'll skip this classifier as another process is working on it
            EnhancedAbstractClassifier finalClassifier1 = classifier;
            logger.info(() -> "skip evaluation due to parallelisation or already done: " + StrUtils.toOptionValue(finalClassifier1));
        }
    }

    private boolean createDoneFile(String name) throws IOException {
        if(isCheckpointSavingEnabled()) {
            // we're checkpointing therefore we need to create a file to say we're done
            String path = savePath + name + "." + DONE_FILE_EXTENSION;
            File file = new File(path);
            if(!file.createNewFile()) {
                if(!file.exists()) {
                    throw new IllegalStateException("failed to create file: " + file);
                }
            }
            return true;
        } else {
            // we're not checkpointing so this should have no effect
            return true;
        }
    }

    private boolean classifierAlreadyFullyBuiltUnchecked(String name) {
        // we're checkpointing so we need to check if the file exists
        // another process may have written to this file, indicating it has already fully built the classifier
        return new File(savePath + name + "." + DONE_FILE_EXTENSION).exists();
    }

    private boolean classifierAlreadyFullyBuilt(String name) {
        if(isCheckpointSavingEnabled()) {
            return classifierAlreadyFullyBuiltUnchecked(name);
        } else {
            // we're not checkpointing therefore no capability for done files
            return false;
        }
    }

    protected EnhancedAbstractClassifier loadClassifier(EnhancedAbstractClassifier classifier) throws Exception {
        trainTimer.suspend();
        trainEstimateTimer.suspend();
        memoryWatcher.suspend();
        if(isCheckpointLoadingEnabled()) {
            final String classifierLoadPath = buildClassifierLoadPath(classifier);
            if(classifier instanceof Checkpointable) {
                ((Checkpointable) classifier).setLoadPath(classifierLoadPath);
                ((Checkpointable) classifier).loadFromCheckpoint();
                // add the resource stats from the classifier (as we may have loaded from checkpoint, therefore need
                // to catch up)
                if(classifier instanceof TrainTimeable) {
                    trainTimer.add(((TrainTimeable) classifier).getTrainTimeNanos());
                    trainEstimateTimer.add(((TrainTimeable) classifier).getTrainEstimateTimeNanos());
                }
                if(classifier instanceof MemoryWatchable) {
                    memoryWatcher.add(((MemoryWatchable) classifier));
                }
            } else {
                // load classifier manually
                classifier =
                        (EnhancedAbstractClassifier) CheckpointUtils.deserialise(classifierLoadPath + CheckpointUtils.checkpointFileName);
                ClassifierResults results = classifier.getTrainResults();
                trainTimer.add(results.getTrainTimeNanos());
                trainEstimateTimer.add(results.getTrainEstimateTimeNanos());
                memoryWatcher.add(results);
            }
        }
        memoryWatcher.unsuspend();
        trainEstimateTimer.unsuspend();
        trainTimer.unsuspend();
        return classifier;
    }

    protected String buildClassifierSavePath(EnhancedAbstractClassifier classifier) {
        return savePath + classifier.getClassifierName() + File.separator;
    }

    protected String buildClassifierLoadPath(EnhancedAbstractClassifier classifier) {
        return loadPath + classifier.getClassifierName() + File.separator;
    }

    protected void saveClassifier(EnhancedAbstractClassifier classifier) throws Exception {
        if(isCheckpointSavingEnabled()) {
            final String classifierSavePath = buildClassifierSavePath(classifier);
            if(classifier instanceof Checkpointable) {
                ((Checkpointable) classifier).setSavePath(classifierSavePath);
                ((Checkpointable) classifier).setSkipFinalCheckpoint(false);
                ((Checkpointable) classifier).saveToCheckpoint();
            } else {
                // save classifier manually
                CheckpointUtils.serialise(classifier, classifierSavePath + CheckpointUtils.checkpointFileName);
            }
        }
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    public Set<EnhancedAbstractClassifier> getBenchmarks() {
        return benchmarks;
    }

    public Ensembler getEnsembler() {
        return ensembler;
    }

    public void setEnsembler(Ensembler ensembler) {
        this.ensembler = ensembler;
    }

    public List<Double> getEnsembleWeights() {
        return ensembleWeights;
    }

    @Override
    public double[] distributionForInstance(Instance testCase) throws Exception {
        Iterator<EnhancedAbstractClassifier> benchmarkIterator = benchmarks.iterator();
        if(benchmarks.size() == 1) {
            return benchmarkIterator.next().distributionForInstance(testCase);
        }
        double[] distribution = new double[numClasses];
        for(int i = 0; i < benchmarks.size(); i++) {
            if(!benchmarkIterator.hasNext()) {
                throw new IllegalStateException("iterator incorrect");
            }
            EnhancedAbstractClassifier benchmark = benchmarkIterator.next();
            double[] constituentDistribution = benchmark.distributionForInstance(testCase);
            ArrayUtilities.multiplyInPlace(constituentDistribution, ensembleWeights.get(i));
            ArrayUtilities.addInPlace(distribution, constituentDistribution);
        }
        ArrayUtilities.normaliseInPlace(distribution);
        return distribution;
    }

    @Override
    public double classifyInstance(Instance testCase) throws Exception {
        return ArrayUtilities.bestIndex(Doubles.asList(distributionForInstance(testCase)), testRand);
    }

    public SerConsumer<Instances> getTrainSetupFunction() {
        return trainSetupFunction;
    }

    public void setTrainSetupFunction(final SerConsumer<Instances> trainSetupFunction) {
        this.trainSetupFunction = trainSetupFunction;
    }

    public Agent getAgent() {
        return agent;
    }

}
