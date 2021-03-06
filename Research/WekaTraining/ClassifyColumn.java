package WekaTraining;

import com.google.common.util.concurrent.SimpleTimeLimiter;
import moa.classifiers.Classifier;
import moa.classifiers.meta.WEKAClassifier;
import moa.options.ClassOption;
import moa.streams.InstanceStream;
import moa.tasks.LearnModel;
import moa.tasks.StandardTaskMonitor;
import weka.core.Instance;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Package: semanticcompression
 * Class: ClassifyColumn
 * Description: Contains the methods necessary to classify a single column.
 */
public class ClassifyColumn {

    // max number of attributes in dataset before M5P is not used
    private static final int MAX_M5P_ATTRIBUTES = 50;
    // the error for a misclassified nominal attribute
    private static final double MISCLASSIFICATION_ERROR = 1.0;
    // time limit for sampling
    private static final long SAMPLE_TIME_LIMIT_SECS = 60;
    // the max number of instances to use to train the model
    private static final int MAX_INSTANCES = 10000000;

    private static StandardTaskMonitor _taskMonitor = null;

    // classifies a single column, either sampling (outputStream == null) or outputting the classified values (outputStream != null)
    public static ColumnData classify(int column, InstanceStream training, InstanceStream testing, double errorThreshold, CompressedOutputStream outputStream) {
        boolean sampling = (outputStream == null);
        boolean numeric = Utilities.useNumericRetrieval(testing.getHeader());

        // create and train the model
        WEKAClassifier model = new CustomWEKAClassifier();
        if (numeric && testing.getHeader().numAttributes() < MAX_M5P_ATTRIBUTES)
            model.baseLearnerOption.setValueViaCLIString("weka.classifiers.trees.M5P");
        else
            model.baseLearnerOption.setValueViaCLIString("weka.classifiers.trees.REPTree");
        model.prepareForUse(getTaskMonitor(), null);

        // setup the LearnModel object
        final LearnModel learn = new LearnModel(model, training, MAX_INSTANCES, 1) {
            protected Object getPreparedClassOption(ClassOption opt) {
                if (opt.getName().equals("learner"))
                    return this.learnerOption.getPreMaterializedObject();
                if (opt.getName().equals("stream"))
                    return this.streamOption.getPreMaterializedObject();
                return super.getPreparedClassOption(opt);
            }
        };

        // run the LearnModel task with a timer if sampling
        // if we run out of memory, just give up on this column instead of quitting the whole program
        Classifier classifier;
        SimpleTimeLimiter timeLimiter = new SimpleTimeLimiter();
        try {
            if (sampling) {
                classifier = timeLimiter.callWithTimeout(new Callable<Classifier>() {
                    public Classifier call() {
                        try {
                            return (Classifier) learn.doMainTask(getTaskMonitor(), null);
                        } catch (OutOfMemoryError e) {
                            System.out.println("Caught OutOfMemoryError: " + e.getMessage());
                            return null;
                        }
                    }
                }, SAMPLE_TIME_LIMIT_SECS, TimeUnit.SECONDS, false);
            } else {
                try {
                    classifier = (Classifier) learn.doMainTask(getTaskMonitor(), null);
                } catch (OutOfMemoryError e) {
                    System.out.println("Caught OutOfMemoryError: " + e.getMessage());
                    classifier = null;
                }
            }
        } catch (Exception e) {
            System.out.println("Exception: " + e.getMessage());
            classifier = null;
        }

        // something went wrong
        if (classifier == null) {
            model = null;
            ColumnData col;
            if (sampling)
                col = new ColumnData(column, Double.POSITIVE_INFINITY, -1, null);
            else
                col = new ColumnData(column, -1, 0, null);
            System.out.println("Classified column: " + col);
            return col;
        }

        // now move on to training
        if (testing == training)
            training.restart();
        //System.out.println("Evaluating model...");

        // calculate root square error if sampling, percent compressed otherwise
        double avgError = 0;
        long numCompressed = 0;
        long numInstances = 0;
        while (testing.hasMoreInstances()) {
            Instance inst = testing.nextInstance();
            numInstances++;
            if (sampling) {
                if (numeric) {
                    avgError += Utilities.percentError(classifier, inst);
                } else if (!Utilities.correctlyClassified(classifier, inst)) {
                    avgError += MISCLASSIFICATION_ERROR;
                }
            } else {
                if (Utilities.compressable(classifier, inst, column, errorThreshold))
                    numCompressed++;
            }
        }
        avgError = Math.abs(avgError / numInstances);
        double percentCompressed = (double) numCompressed / (double) numInstances;

        // this reduces the classifier's size before serialization
        if (classifier instanceof CustomWEKAClassifier) {
            ((CustomWEKAClassifier) classifier).trimSize();
            avgError = 1.0 / ((CustomWEKAClassifier) classifier).getDependencies(training.getHeader()).length;
        }

        ColumnData outputCol;
        if (sampling)
            outputCol = new ColumnData(column, avgError, -1, null);
        else
            outputCol = new ColumnData(column, -1, percentCompressed, classifier);

        System.out.println("Classified column: " + outputCol);
        return outputCol;
    }

    // static task monitor to print status to stdout
    private static StandardTaskMonitor getTaskMonitor() {
        if (_taskMonitor == null) {
            _taskMonitor = new StandardTaskMonitor() {
                //long lastTime = 0;
                public void setCurrentActivityDescription(String activity) {
                    //System.out.println(activity);
                    super.setCurrentActivityDescription(activity);
                }
                public void setCurrentActivityFractionComplete(double fracComplete) {
                    //long currTime = System.currentTimeMillis();
                    //if (currTime >= lastTime + PROGRESS_PRINT_EVERY && fracComplete >= 0) {
                        //System.out.println("Progress: " + String.format("%.1f%%", fracComplete * 100));
                        //lastTime = currTime;
                    //}
                    super.setCurrentActivityFractionComplete(fracComplete);
                }
            };
        }
        return _taskMonitor;
    }
}
