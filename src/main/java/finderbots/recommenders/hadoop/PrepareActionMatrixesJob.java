package finderbots.recommenders.hadoop;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.util.ToolRunner;
import org.apache.mahout.cf.taste.hadoop.EntityPrefWritable;
import org.apache.mahout.cf.taste.hadoop.ToItemPrefsMapper;
import org.apache.mahout.cf.taste.hadoop.item.ItemIDIndexMapper;
import org.apache.mahout.cf.taste.hadoop.item.ItemIDIndexReducer;
import org.apache.mahout.cf.taste.hadoop.item.RecommenderJob;
import org.apache.mahout.cf.taste.hadoop.item.ToUserVectorsReducer;
import org.apache.mahout.cf.taste.hadoop.preparation.ToItemVectorsMapper;
import org.apache.mahout.common.AbstractJob;
import org.apache.mahout.common.HadoopUtil;
import org.apache.mahout.math.VarIntWritable;
import org.apache.mahout.math.VarLongWritable;
import org.apache.mahout.math.VectorWritable;

import java.util.List;
import java.util.Map;

/**
 * User: pat
 * Date: 4/5/13
 * Time: 8:18 AM
 *
 * <p>This job will take an input dir, and recursively look for text files that contain action logs
 * it will build DistributedRowMatix (es) for each action type found. The files are expected to be
 * tsv or csv of the form:<p/>
 * <p>timestamp userID actionType itemID
 * <p/>
 * <p>All are string except the timestamp, which is ignored. The order is defined by options and any
 * extra columns are ignored.
 * The actionType is something like view, purchase, thumbs-up, or follow and is defined in options.
 * The userID and itemID are strings that uniquely describe the objects and are translated into
 * internal {@code Long}, which are used in the rest of the jobs to identify the users and items.
 * <p/>
 * <p>NOTE: These internal IDs are used everywhere in this job chain to id users and items. The
 * size of the space defined by these is very important and must be maintained. The matrixes
 * created will be of size #-of-user by #-of-items. Even when any row or column might be empty.
 * In order to calculate a transpose or matrix multiply the size must be known. The indexes for
 * the internal to external and the reverse are bidirectional mappings kept in files created by
 * this job.
 * <p/>
 * <p>This will take a root output dir and expect the following layout. It assumes
 * that the input has internal ids that Mahout can use and that the largest dimension of either
 * matrix defines the space. So if matrix B has the most users, it is the actual number of users
 * and the same for items. It further assumes the files are in Mahout format so no timestamp and
 * no action id.
 * <p/>
 * <p>inpur-dir
 * <p>|_ primary-action/primary-action-tsv-files of the internal mahout format<p/>
 * <p>\_ secondary-action/secondary-action-tsv-files<p/>
 * <p/>
 * <p>output-dir<p/>
 * <p>|_ primary-pref-matrix<p/>
 * <p>\_ secondary-pref-matrix
 * <p/>
 */

public final class PrepareActionMatrixesJob extends AbstractJob {

    public static final String NUM_USERS = "numUsers.bin";
    public static final String ITEMID_INDEX1 = "itemIDIndex1";
    public static final String USER_VECTORS1 = "userVectors1";
    public static final String ACTION_B_TRANSPOSE_MATRIX_PATH = "actionBTransposeMatrix";

    public static final String NUM_USERS2 = "numUsers2.bin";
    public static final String ITEMID_INDEX2 = "itemIDIndex2";
    public static final String USER_VECTORS2 = "userVectors2";
    public static final String ACTION_A_TRANSPOSE_MATRIX_PATH = "actionATransposeMatrix";

    private static final int DEFAULT_MIN_PREFS_PER_USER = 1;
    //public static final String ACTION_B_PREFS = "primary";
    //public static final String ACTION_A_PREFS = "secondary";

    @Override
    public int run(String[] args) throws Exception {

        addInputOption();
        addOutputOption();
        addOption("maxPrefsPerUser", "mppu", "max number of preferences to consider per user, "
            + "users with more preferences will be sampled down");
        addOption("minPrefsPerUser", "mp", "ignore users with less preferences than this "
            + "(default: " + DEFAULT_MIN_PREFS_PER_USER + ')', String.valueOf(DEFAULT_MIN_PREFS_PER_USER));
        addOption("booleanData", "b", "Treat input as without pref values", Boolean.FALSE.toString());
        addOption("primaryPrefs", "pp", "Where are the user prefs for Primary actions", true);
        addOption("secondaryPrefs", "sp", "Where are the user prefs for Secondary actions", true);
        //addOption("matrixA", "ma", "Where to put matrix of user prefs for Secondary actions", true);
        //addOption("matrixB", "mb", "Where to put matrix of user prefs for Primary actions", true);

        Map<String, List<String>> parsedArgs = parseArguments(args);
        if (parsedArgs == null) {
            return -1;
        }

        int minPrefsPerUser = Integer.parseInt(getOption("minPrefsPerUser"));
        boolean booleanData = Boolean.valueOf(getOption("booleanData"));

        // Suck in Action B from the prefs file(s)
        //convert items to an internal index
        Path actionBPrefsPath = new Path(getOption("input"), getOption("primaryPrefs"));
        Job itemIDIndex = prepareJob(actionBPrefsPath, getOutputPath(ITEMID_INDEX1), TextInputFormat.class,
            ItemIDIndexMapper.class, VarIntWritable.class, VarLongWritable.class, ItemIDIndexReducer.class,
            VarIntWritable.class, VarLongWritable.class, SequenceFileOutputFormat.class
        );
        itemIDIndex.setCombinerClass(ItemIDIndexReducer.class);
        boolean succeeded = itemIDIndex.waitForCompletion(true);
        if (!succeeded) {
            return -1;
        }
        //convert user preferences into a vector per user
        Job toUserVectors = prepareJob(actionBPrefsPath,
            getOutputPath(USER_VECTORS1),
            TextInputFormat.class,
            ToItemPrefsMapper.class,
            VarLongWritable.class,
            booleanData ? VarLongWritable.class : EntityPrefWritable.class,
            ToUserVectorsReducer.class,
            VarLongWritable.class,
            VectorWritable.class,
            SequenceFileOutputFormat.class
        );
        toUserVectors.getConfiguration().setBoolean(RecommenderJob.BOOLEAN_DATA, booleanData);
        toUserVectors.getConfiguration().setInt(ToUserVectorsReducer.MIN_PREFERENCES_PER_USER, minPrefsPerUser);
        succeeded = toUserVectors.waitForCompletion(true);
        if (!succeeded) {
            return -1;
        }
        //we need the number of users later
        int numberOfActionBUsers = (int) toUserVectors.getCounters().findCounter(ToUserVectorsReducer.Counters.USERS).getValue();
        //build the rating matrix
        Job toItemVectors = prepareJob(getOutputPath(USER_VECTORS1), getOutputPath(ACTION_B_TRANSPOSE_MATRIX_PATH),
            ToItemVectorsMapper.class, IntWritable.class, VectorWritable.class, ToItemVectorsReducer.class,
            IntWritable.class, VectorWritable.class);
        toItemVectors.setCombinerClass(ToItemVectorsReducer.class);

        /* configure sampling regarding the uservectors */
        if (hasOption("maxPrefsPerUser")) {
            int samplingSize = Integer.parseInt(getOption("maxPrefsPerUser"));
            toItemVectors.getConfiguration().setInt(ToItemVectorsMapper.SAMPLE_SIZE, samplingSize);
        }
        succeeded = toItemVectors.waitForCompletion(true);
        if (!succeeded) {
            return -1;
        }

        // Suck in Action A from the prefs file(s)
        //convert items to an internal index
        Path actionAPrefsPath = new Path(getOption("input"), getOption("secondaryPrefs"));
        itemIDIndex = prepareJob(actionAPrefsPath, getOutputPath(ITEMID_INDEX2), TextInputFormat.class,
            ItemIDIndexMapper.class, VarIntWritable.class, VarLongWritable.class, ItemIDIndexReducer.class,
            VarIntWritable.class, VarLongWritable.class, SequenceFileOutputFormat.class
        );
        itemIDIndex.setCombinerClass(ItemIDIndexReducer.class);
        succeeded = itemIDIndex.waitForCompletion(true);
        if (!succeeded) {
            return -1;
        }
        //convert user preferences into a vector per user
        toUserVectors = prepareJob(actionAPrefsPath,
            getOutputPath(USER_VECTORS2),
            TextInputFormat.class,
            ToItemPrefsMapper.class,
            VarLongWritable.class,
            booleanData ? VarLongWritable.class : EntityPrefWritable.class,
            ToUserVectorsReducer.class,
            VarLongWritable.class,
            VectorWritable.class,
            SequenceFileOutputFormat.class
        );
        toUserVectors.getConfiguration().setBoolean(RecommenderJob.BOOLEAN_DATA, booleanData);
        toUserVectors.getConfiguration().setInt(ToUserVectorsReducer.MIN_PREFERENCES_PER_USER, minPrefsPerUser);
        succeeded = toUserVectors.waitForCompletion(true);
        if (!succeeded) {
            return -1;
        }
        //we need the number of users later
        int numberOfActionAUsers = (int) toUserVectors.getCounters().findCounter(ToUserVectorsReducer.Counters.USERS).getValue();
        //build the rating matrix
        toItemVectors = prepareJob(getOutputPath(USER_VECTORS2), getOutputPath(ACTION_A_TRANSPOSE_MATRIX_PATH),
            ToItemVectorsMapper.class, IntWritable.class, VectorWritable.class, ToItemVectorsReducer.class,
            IntWritable.class, VectorWritable.class);
        toItemVectors.setCombinerClass(ToItemVectorsReducer.class);

        /* configure sampling regarding the uservectors */
        if (hasOption("maxPrefsPerUser")) {
            int samplingSize = Integer.parseInt(getOption("maxPrefsPerUser"));
            toItemVectors.getConfiguration().setInt(ToItemVectorsMapper.SAMPLE_SIZE, samplingSize);
        }
        succeeded = toItemVectors.waitForCompletion(true);
        if (!succeeded) {
            return -1;
        }

        // Done creating the two matrices from pref data these will be used by the XRecommenderJob
        if (numberOfActionBUsers != numberOfActionAUsers) {
            return -1;
        }
        HadoopUtil.writeInt(numberOfActionBUsers, getOutputPath(NUM_USERS), getConf());

        //now move the DistributedRowMatrix(es) to the desired output location
        // move getOutputPath(ACTION_1_MATRIX) to getOption("matrixA")
        //JobConf conf = new JobConf();
        //FileSystem fs = getOutputPath().getFileSystem(conf);
        //fs.rename(getOutputPath(ACTION_1_MATRIX),new Path(getOption("matrixA")));
        //fs.rename(getOutputPath(ACTION_2_MATRIX),new Path(getOption("matrixB")));

        return 0;
    }

    public static void main(String[] args) throws Exception {
        ToolRunner.run(new Configuration(), new PrepareActionMatrixesJob(), args);
    }

}
