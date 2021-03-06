package sh.diqi.circuseyes;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.DescriptorExtractor;
import org.opencv.features2d.FeatureDetector;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by zengjing on 2017/12/23.
 */

public class FMCGDetector {

    private static final boolean DEBUG = true;

    public static enum BG {
        GREEN,
        RED,
        BLUE,
        BLACK,
        GRAY,
        WHITE;

        private BG() {

        }
    }

    public interface DetectCallback {
        public void onFrame(final ByteBuffer frame);

        public void onResult(final List<Pair<String, Double>> results);
    }

    private static final String TAG = FMCGDetector.class.getSimpleName();

    private static final double THRESHOLD_THRESH = 128;
    private static final double THRESHOLD_MAXVAL = 255;
    private static final int THRESHOLD_TYPE = 1;
    private static final double KERNEL_WIDTH = 8;
    private static final double KERNEL_HEIGHT = 3;
    private static final int DILATE_SIZE = 256;
    private static final int DILATE_ITERATIONS = 2;
    private static final float MINIMUM_CONFIDENCE = 0.1f;
    private static final float MINIMUM_ROI_AREA = 2048;
    private static final float MAXIMUM_ROI_AREA = 2048 * 1536;
    private static final int MINIMUM_ROI_NUM = 3;

    private Context mContext;
    private Classifier mDetector;
    private int mImageWidth;
    private int mImageHeight;
    private BG mImageBgColor;

    private int mInputSize = 300;
    private int mSensorOrientation;

    private Bitmap mBitmap;

    private DetectCallback mDetectCallback;

    private FeatureDetector mFeatureDetector;
    private DescriptorExtractor mDescriptorExtractor;
    private BFMatcher mBFMatcher;
    private Map<String, List<Map<String, Object>>> mFeatureIndex;

    public FMCGDetector(final Context context, final String candidatesDir) throws IOException {
        mContext = context;
        mFeatureDetector = FeatureDetector.create(FeatureDetector.BRISK);
        mDescriptorExtractor = DescriptorExtractor.create(DescriptorExtractor.BRISK);
        mBFMatcher = BFMatcher.create(Core.NORM_HAMMING, false);
        buildFeatureIndex(candidatesDir);
    }

    public FMCGDetector(final Context context, final String modelFile, final String labelFile, final int width, final int height, final BG color) throws IOException {
        mContext = context;
        mImageWidth = width;
        mImageHeight = height;
        mImageBgColor = color;
        mSensorOrientation = 90 - getScreenOrientation(context);
//        mInputSize = Math.max(width, height);
        mDetector = TensorFlowObjectDetectionAPIModel.create(context.getAssets(), modelFile, labelFile, mInputSize);
    }

    public FMCGDetector(final Context context, final String modelFile, final String labelFile, final int width, final int height, final BG color, final DetectCallback callback) throws NullPointerException, IOException {
        this(context, modelFile, labelFile, width, height, color);
        if (callback == null) {
            throw new NullPointerException("callback should not be null");
        }
        mDetectCallback = callback;
        mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
    }

    private void buildFeatureIndex(String candidatesDir) throws IOException {
        mFeatureIndex = new HashMap<>();
        String dirPath = candidatesDir.split("file:///android_asset/")[1];
        AssetManager assetManager = mContext.getAssets();
        for (String file : assetManager.list(dirPath)) {
            String filePath = dirPath + "/" + file;
            Log.d(TAG, filePath);
            File cacheFile = new File(mContext.getCacheDir() + "/" + file);
            try {
                InputStream is = mContext.getAssets().open(filePath);
                int size = is.available();
                byte[] buffer = new byte[size];
                is.read(buffer);
                is.close();
                FileOutputStream fos = new FileOutputStream(cacheFile);
                fos.write(buffer);
                fos.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            String name = file.split("_")[0];
            Mat img = Imgcodecs.imread(cacheFile.getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
//            Imgproc.resize(img, img, new Size(), 0.8, 0.8, Imgproc.INTER_AREA);
            Log.d(TAG, cacheFile.getAbsolutePath() + ": " + img.size().toString());
            MatOfKeyPoint kp = new MatOfKeyPoint();
            mFeatureDetector.detect(img, kp);
            Log.d(TAG, "kp: " + kp.size().toString());
            Mat des = new Mat();
            mDescriptorExtractor.compute(img, kp, des);
            Log.d(TAG, "des: " + des.size().toString());
            if (!mFeatureIndex.containsKey(name)) {
                mFeatureIndex.put(name, new ArrayList<Map<String, Object>>());
            }
            Map<String, Object> feature = new HashMap<>();
            feature.put("kp", kp);
            feature.put("des", des);
            feature.put("img", img);
            mFeatureIndex.get(name).add(feature);
        }
    }

    public List<String> analyze(Bitmap bitmap) {
        List<String> results = new ArrayList<>();
        final long startTime = SystemClock.uptimeMillis();
        Mat origin = new Mat();
        Utils.bitmapToMat(bitmap, origin);
        Mat img = new Mat();
        Imgproc.cvtColor(origin, img, Imgproc.COLOR_BGR2GRAY);
//        Imgproc.resize(img, img, new Size(), 0.6, 0.6, Imgproc.INTER_AREA);
        MatOfKeyPoint kp = new MatOfKeyPoint();
        mFeatureDetector.detect(img, kp);
        Mat des = new Mat();
        mDescriptorExtractor.compute(img, kp, des);
        for (Map.Entry<String, List<Map<String, Object>>> entry: mFeatureIndex.entrySet()) {
            String name = entry.getKey();
            List<Map<String, Object>> faces = entry.getValue();
//            Log.d(TAG, name + ": " + faces.size());
            boolean matched = false;
            StringBuilder score = new StringBuilder();
            for (Map<String, Object> face : faces) {
                List<MatOfDMatch> matches = new ArrayList<>();
                mBFMatcher.knnMatch((Mat)face.get("des"), des, matches, 2);
                int good = 0;
                for (MatOfDMatch match : matches) {
                    List<DMatch> dMatches = match.toList();
                    if (dMatches.size() >= 2 && dMatches.get(0).distance < 0.75 * dMatches.get(1).distance) {
                        good++;
                    }
                }
//                Log.d(TAG, "matches: " + matches.size() + ", good: " + good);
                if (good > 5) {
                    matched = true;
                    score.append(good).append(", ");
                    break;
                }
            }
            if (matched) {
                results.add(name + ": " + score.toString());
            }
        }
        long spent = SystemClock.uptimeMillis() - startTime;
//        Log.d(TAG, spent + " ms taken to analyze.");
        return results;
    }

    public Bitmap drawRects(Bitmap bitmap, List<RectF> rects, int red, int green, int blue) {
        Mat origin = new Mat();
        Utils.bitmapToMat(bitmap, origin);
        for (RectF rect : rects) {
            Imgproc.rectangle(origin, new Point(rect.left, rect.top), new Point(rect.right, rect.bottom), new Scalar(red, green, blue), 2);
        }
        Utils.matToBitmap(origin, bitmap);
        return bitmap;
    }

    public List<RectF> getRois(Bitmap bitmap, BG color) {
        long start = System.currentTimeMillis();
        Mat origin = new Mat();
        Utils.bitmapToMat(bitmap, origin);
//        Mat image = new Mat();
//        Imgproc.cvtColor(origin, image, Imgproc.COLOR_BGR2HSV);
//        Mat mask = new Mat();
//        Pair<Scalar, Scalar> bounding = getBounding(color);
//        Core.inRange(image, bounding.first, bounding.second, mask);
//        Imgproc.threshold(mask, mask, THRESHOLD_THRESH, THRESHOLD_MAXVAL, THRESHOLD_TYPE);
//        Mat kernel = Imgproc.getStructuringElement(Imgproc.CV_SHAPE_ELLIPSE, new Size(KERNEL_WIDTH, KERNEL_HEIGHT));
//        Imgproc.dilate(mask, mask, kernel, new Point(), DILATE_ITERATIONS);
//        List<MatOfPoint> contours = new ArrayList<>();
//        Imgproc.findContours(mask, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
//        Collections.sort(contours, new Comparator<MatOfPoint>() {
//            @Override
//            public int compare(MatOfPoint c1, MatOfPoint c2) {
//                double a1 = Imgproc.contourArea(c1);
//                double a2 = Imgproc.contourArea(c2);
//                return a2 > a1 ? 1 : (a2 == a1 ? 0 : -1);
//            }
//        });

        List<RectF> boxes = new ArrayList<>();
//        for (MatOfPoint contour : contours) {
//            Rect rect = Imgproc.boundingRect(contour);
//            RectF dilated = new RectF(Math.max(rect.x - DILATE_SIZE, 0),
//                    Math.max(rect.y - DILATE_SIZE, 0),
//                    Math.min(rect.x + rect.width + DILATE_SIZE, origin.width()),
//                    Math.min(rect.y + rect.height + DILATE_SIZE, origin.height()));
//            boolean matched = false;
//            for (int i = 0; i < boxes.size(); i++) {
//                RectF box = boxes.get(i);
//                if (box.contains(dilated)) {
//                    matched = true;
//                    break;
//                }
//                if (box.contains(dilated.centerX(), dilated.centerY())) {
//                    box.set(Math.min(box.left, dilated.left),
//                            Math.min(box.top, dilated.top),
//                            Math.max(box.right, dilated.right),
//                            Math.max(box.bottom, dilated.bottom));
//                    boxes.set(i, box);
//                    matched = true;
//                    break;
//                }
//            }
//            if (!matched) {
//                boxes.add(new RectF(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height));
//            }
//        }

        if (boxes.size() == 0) {
            boxes = Collections.singletonList(new RectF(0, 0, origin.width(), origin.height()));
        }

        List<RectF> rois = new ArrayList<>();
        for (RectF box : boxes) {
            if (box.width() * box.height() > MINIMUM_ROI_AREA) {
                rois.add(box);
//                if (box.width() * box.height() > MAXIMUM_ROI_AREA) {
//                    float halfWidth = (float) Math.floor(box.width() / 2d);
//                    float halfHeight = (float) Math.floor(box.height() / 2d);
//                    for (float left = 0; left + halfWidth <= box.width(); left += halfWidth) {
//                        rois.add(new RectF(box.left + left, box.top, box.left + left + halfWidth, box.bottom));
//                    }
//                    for (float top = 0; top + halfHeight <= box.height(); top += halfHeight) {
//                        rois.add(new RectF(box.left, box.top + top, box.right, box.top + top + halfHeight));
//                    }
//                    for (float left = 0; left + halfWidth <= box.width(); left += halfWidth) {
//                        for (float top = 0; top + halfHeight <= box.height(); top += halfHeight) {
//                            rois.add(new RectF(box.left + left, box.top + top, box.left + left + halfWidth, box.top + top + halfHeight));
//                        }
//                    }
////                    float atomSize = (float) Math.min(Math.floor(origin.width() / 3d), Math.floor(origin.height() / 3d));
////                    float atomStep = (float) Math.floor(atomSize / 2f);
////                    for (float left = 0; left < origin.width(); left += atomStep) {
////                        for (float top = 0; top < origin.height(); top += atomStep) {
////                            rois.add(new RectF(left, top, Math.min(left + atomSize, origin.width()), Math.min(top + atomSize, origin.height())));
////                        }
////                    }
//                }
            }
        }

        long spent = System.currentTimeMillis() - start;
        Log.d(TAG, spent + " ms taken to get rects.");

        if (DEBUG) {
            String dirName = md5(bitmap) + "_" + MINIMUM_ROI_AREA;
            File dir = new File(mContext.getExternalFilesDir(null), dirName);
            if (dir.exists() || dir.mkdirs()) {
                for (RectF box : rois) {
                    saveFile(Bitmap.createBitmap(bitmap, Math.round(box.left), Math.round(box.top), Math.round(box.width()), Math.round(box.height())),
                            dirName, "roi_" + spent + "ms_b" + rois.size() + "_" + box.toShortString() + "_" + box.width() + "×" + box.height() + ".jpg");
                }
            }
        }

//        boxes.clear();
        return rois;
    }

    public List<Classifier.Recognition> recognize(Bitmap origin) {
        final long startTime = SystemClock.uptimeMillis();
        Bitmap cropped = Bitmap.createBitmap(mInputSize, mInputSize, Bitmap.Config.ARGB_8888);
        Matrix frameToCropTransform =
                getTransformationMatrix(
                        origin.getWidth(), origin.getHeight(),
                        mInputSize, mInputSize,
                        mSensorOrientation, false);
        Matrix cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);
        Canvas canvas = new Canvas(cropped);
        canvas.drawBitmap(origin, frameToCropTransform, null);
        final List<Classifier.Recognition> results = new ArrayList<>();
        for (Classifier.Recognition result : mDetector.recognizeImage(cropped)) {
            final RectF location = result.getLocation();
            if (location != null && result.getConfidence() >= MINIMUM_CONFIDENCE) {
                cropToFrameTransform.mapRect(location);
                result.setLocation(location);
                results.add(result);
            }
        }
        Log.d(TAG, (SystemClock.uptimeMillis() - startTime) + " ms taken to recognize bitmap.");
        return results;
    }

    public List<Classifier.Recognition> merge(List<Classifier.Recognition> candidates) {
        List<Classifier.Recognition> chosen = new ArrayList<>();
        Collections.sort(candidates, new Comparator<Classifier.Recognition>() {
            @Override
            public int compare(Classifier.Recognition r1, Classifier.Recognition r2) {
                return r2.getConfidence() > r1.getConfidence() ? 1 : (r2.getConfidence().equals(r1.getConfidence()) ? 0 : -1);
            }
        });
        for (Classifier.Recognition candidate : candidates) {
            if (candidate.getId().equals("r")) {
                continue;
            }
            Classifier.Recognition matched = null;
            for (int i = 0; i < chosen.size(); i++) {
                Classifier.Recognition object = chosen.get(i);
                if (object.getLocation().contains(candidate.getLocation()) &&
                        object.getId().equals(candidate.getId())) {
                    matched = object;
                    break;
                }
                if (object.getLocation().intersect(candidate.getLocation()) &&
                        object.getLocation().contains(candidate.getLocation().centerX(), candidate.getLocation().centerY()) &&
                        object.getId().equals(candidate.getId())) {
                    object.setLocation(new RectF(
                            Math.min(object.getLocation().left, candidate.getLocation().left),
                            Math.min(object.getLocation().top, candidate.getLocation().top),
                            Math.max(object.getLocation().right, candidate.getLocation().right),
                            Math.max(object.getLocation().bottom, candidate.getLocation().bottom)
                    ));
                    matched = object;
                    break;
                }
            }
            if (matched == null) {
                chosen.add(candidate);
            }
        }
        return chosen;
    }

    public List<Classifier.Recognition> recognize(Bitmap origin, RectF roi) {
        final long startTime = SystemClock.uptimeMillis();
        Bitmap bitmap = Bitmap.createBitmap(origin, Math.round(roi.left), Math.round(roi.top), Math.round(roi.width()), Math.round(roi.height()));
        Bitmap cropped = Bitmap.createBitmap(mInputSize, mInputSize, Bitmap.Config.RGB_565);
        Matrix frameToCropTransform =
                getTransformationMatrix(
                        origin.getWidth(), origin.getHeight(),
                        mInputSize, mInputSize,
                        mSensorOrientation, false);
        Canvas canvas = new Canvas(cropped);
        canvas.drawBitmap(origin, frameToCropTransform, null);
        final List<Classifier.Recognition> results = recognize(cropped);
        long spent = SystemClock.uptimeMillis() - startTime;
        Log.d(TAG, spent + " ms taken to analyze roi.");

        if (DEBUG) {
            String dirName = md5(origin) + "_" + MINIMUM_ROI_AREA;
            File dir = new File(mContext.getExternalFilesDir(null), dirName);
            if (dir.exists() || dir.mkdirs()) {
                Mat mat = new Mat();
                Utils.bitmapToMat(bitmap, mat);
                for (Classifier.Recognition result : results) {
                    RectF rect = result.getLocation();
                    Imgproc.rectangle(mat, new Point(rect.left, rect.top), new Point(rect.right, rect.bottom), new Scalar(255, 0, 0), 2);
                }
                Utils.matToBitmap(mat, bitmap);
                saveFile(bitmap, dirName, "rec_" + spent + "ms_" + roi.toShortString() + "_" + roi.width() + "×" + roi.height() + ".jpg");
            }
        }

        for (int i = 0; i < results.size(); i++) {
            Classifier.Recognition result = results.get(i);
            final RectF location = result.getLocation();
            location.offset(roi.left, roi.top);
            result.setLocation(location);
            results.set(i, result);
        }
        return results;
    }

    public List<Classifier.Recognition> recognize(Bitmap origin, List<RectF> rois) {
        for (RectF roi : rois) {
            Log.d(TAG, roi.toString());
        }
        final long startTime = SystemClock.uptimeMillis();
        final List<Classifier.Recognition> results = new ArrayList<>();
        for (RectF roi : rois) {
            results.add(new Classifier.Recognition("r", "roi", 1f, roi));
            results.addAll(recognize(origin, roi));
        }
        Log.d(TAG, (SystemClock.uptimeMillis() - startTime) + " millis taken to recognize bitmap.");
        return results;
    }

    public List<Classifier.Recognition> recognize(Bitmap origin, BG color) {
        return recognize(origin, getRois(origin, color));
    }

    public void close() {
        if (mBitmap != null && !mBitmap.isRecycled()) {
            mBitmap.recycle();
        }
    }

    private Pair<Scalar, Scalar> getBounding(final BG color) {
        switch (color) {
            case BLACK:
                return new Pair<>(new Scalar(0, 0, 0), new Scalar(180, 255, 220));
            case WHITE:
                return new Pair<>(new Scalar(0, 0, 46), new Scalar(180, 43, 255));
            case BLUE:
                return new Pair<>(new Scalar(100, 43, 46), new Scalar(124, 255, 255));
            case RED:
                return new Pair<>(new Scalar(0, 43, 46), new Scalar(10, 255, 255));
            case GREEN:
            default:
                return new Pair<>(new Scalar(35, 43, 46), new Scalar(99, 255, 255));
        }
    }

    private boolean isInside(Rect a, Rect b) {
        if (a.x >= b.x && a.x <= b.x + b.width && a.x + a.width >= b.x && a.x + a.width <= b.x + b.width && a.y >= b.y && a.y <= b.y + b.height && a.y + a.height >= b.y && a.y + a.height <= b.y + b.height) {
            return true;
        }
        int aCenterX = a.x + a.width / 2;
        int aCenterY = a.y + a.height / 2;
        return aCenterX >= b.x && aCenterX <= b.x + b.width && aCenterY >= b.y && aCenterY <= b.y + b.height;
    }

    private String md5(Bitmap bitmap) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
        byte[] bitmapBytes = baos.toByteArray();
        try {
            MessageDigest m = MessageDigest.getInstance("MD5");
            m.update(bitmapBytes, 0, bitmapBytes.length);
            return new BigInteger(1, m.digest()).toString(16);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return String.valueOf(SystemClock.currentThreadTimeMillis() / 1000 / 60);
        }
    }

    private void saveFile(Bitmap bitmap, String dir, String file) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(mContext.getExternalFilesDir(dir), file));
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private int getScreenOrientation(Context context) {
        switch (((Activity) context).getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_0:
            default:
                return 0;
        }
    }

    private Matrix getTransformationMatrix(
            final int srcWidth,
            final int srcHeight,
            final int dstWidth,
            final int dstHeight,
            final int applyRotation,
            final boolean maintainAspectRatio) {
        final Matrix matrix = new Matrix();
        if (applyRotation != 0) {
            matrix.postTranslate(-srcWidth / 2.0f, -srcHeight / 2.0f);
            matrix.postRotate(applyRotation);
        }
        final boolean transpose = (Math.abs(applyRotation) + 90) % 180 == 0;
        final int inWidth = transpose ? srcHeight : srcWidth;
        final int inHeight = transpose ? srcWidth : srcHeight;
        if (inWidth != dstWidth || inHeight != dstHeight) {
            final float scaleFactorX = dstWidth / (float) inWidth;
            final float scaleFactorY = dstHeight / (float) inHeight;
            if (maintainAspectRatio) {
                final float scaleFactor = Math.max(scaleFactorX, scaleFactorY);
                matrix.postScale(scaleFactor, scaleFactor);
            } else {
                matrix.postScale(scaleFactorX, scaleFactorY);
            }
        }
        if (applyRotation != 0) {
            matrix.postTranslate(dstWidth / 2.0f, dstHeight / 2.0f);
        }
        return matrix;
    }

    private static boolean isInit;

    static {
        if (!isInit) {
            OpenCVLoader.initDebug();
            isInit = true;
        }
    }
}
