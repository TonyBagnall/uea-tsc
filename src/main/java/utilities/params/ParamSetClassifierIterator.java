package utilities.params;

import org.apache.commons.collections4.Transformer;
import org.apache.commons.collections4.iterators.TransformIterator;
import weka.classifiers.Classifier;
import weka.core.OptionHandler;

import java.util.Iterator;
import java.util.function.Supplier;

public class ParamSetClassifierIterator extends TransformIterator<ParamSet, Classifier> {
    private Supplier<Classifier> classifierBuilder;

    public ParamSetClassifierIterator(final Iterator<ParamSet> paramSetIterator,
                                      final Supplier<Classifier> classifierBuilder) {
        super(paramSetIterator);
        setClassifierBuilder(classifierBuilder);
    }

    public Supplier<Classifier> getClassifierBuilder() {
        return classifierBuilder;
    }

    public void setClassifierBuilder(final Supplier<Classifier> classifierBuilder) {
        this.classifierBuilder = classifierBuilder;
        setTransformer(paramSet -> {
            Classifier classifier = classifierBuilder.get();
            ParamHandler.setParams(classifier, paramSet);
            return classifier;
        });
    }
}
