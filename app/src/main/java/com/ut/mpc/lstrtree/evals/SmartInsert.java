package com.ut.mpc.lstrtree.evals;

import android.content.Context;
import android.util.Log;

import com.ut.mpc.etch.Eval;
import com.ut.mpc.etch.MultiProfiler;
import com.ut.mpc.etch.Stabilizer;
import com.ut.mpc.lstrtree.SQLiteNaive;
import com.ut.mpc.lstrtree.SQLiteRTree;
import com.ut.mpc.setup.Constants;
import com.ut.mpc.utils.GPSLib;
import com.ut.mpc.utils.LSTFilter;
import com.ut.mpc.utils.STPoint;
import com.ut.mpc.utils.STRegion;
import com.ut.mpc.utils.STStorage;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Basic window query operation
 * <li> file - name of file to insert data </li>
 * <li> numPoints - number of data points to insert from beginning of file</li>
 * <li> append - True/False for appending this dataset to db or clearing it first</li>
 * <li> type - SQLiteRTree for rtree, anything else for NaiveTableStore </li>
 */
public class SmartInsert implements Eval {

    private static final String TAG = SmartInsert.class.getSimpleName();

    @Override
    public void execute(Context ctx, JSONObject options) throws JSONException {
        int numPoints = Integer.valueOf(options.getString("numPoints"));
        final String fileName = options.getString("file");
        final double smartInsVal = Double.valueOf(options.getString("smartInsVal"));
        String type = options.getString("type");
        String dataType = options.getString("dataType");

        STStorage helper, other;
        boolean isRTree = ("SQLiteRTree").equals(type);
        if(isRTree){
            System.out.println("setting up db type: SQLiteRTree");
            helper = new SQLiteRTree(ctx, "RTreeMain");
            other = new SQLiteNaive(ctx, "SpatialTableMain");
            other.clear();
        } else {
            System.out.println("setting up db type: SpatialTableMain");
            helper = new SQLiteNaive(ctx, "SpatialTableMain");
            other = new SQLiteNaive(ctx, "RTreeMain");
            other.clear();
        }

        if("cabs".equals(dataType)){
            System.out.println("setting up data type: Cabs");
            Constants.setCabDefaults();
        } else {
            System.out.println("setting up data type: Mobility");
            Constants.setMobilityDefaults();
        }

        Constants.SmartInsert.INS_THRESH = smartInsVal;
        final LSTFilter lstFilter = new LSTFilter(helper);
        lstFilter.setKDCache(true);
        lstFilter.setSmartInsert(true);

        lstFilter.clear();

        final String filePath = "/sdcard/Crawdad/" + fileName;
        Stabilizer stabFunc = new Stabilizer(){
            @Override
            public void task(Object data) {
                //DBPrepare.populateDB(lstFilter, filePath, 25, smartInsVal);
                //lstFilter.clear();
            }
        };

        
        int xIndex, yIndex, tIndex;
        String delimiter;
        if("cabs".equals(dataType)) {
            xIndex = 1;
            yIndex = 0;
            tIndex = 3;
            delimiter = " ";
        } else {
            xIndex = 1;
            yIndex = 2;
            tIndex = 0;
            delimiter = "\\s+";
        }

        MultiProfiler.init(this, ctx);
        MultiProfiler.startProfiling(TAG + "_" + numPoints + "_" + fileName + "_" + smartInsVal + "_" + type);
        MultiProfiler.startMark(stabFunc, null, "SI");
        try {
            BufferedReader br = new BufferedReader(new FileReader(filePath));
            String line;
            line = br.readLine();
            String[] split = line.split(delimiter);
            //Log.d(TAG, "index0:_" + split[0] + "_");
            //Log.d(TAG, "index1:_" + split[1] + "_");
            //Log.d(TAG, "index2:_" + split[2] + "_");
            STPoint point = new STPoint(Float.valueOf(split[xIndex]),Float.valueOf(split[yIndex]),Float.valueOf(split[tIndex]));

            int count = 1;
            lstFilter.insert(point);
            while (((line = br.readLine()) != null) && count < numPoints) {
                split = line.split(delimiter);
                point = new STPoint(Float.valueOf(split[xIndex]),Float.valueOf(split[yIndex]),Float.valueOf(split[tIndex]));
                //Log.d(TAG, "index0:_" + split[0] + "_");
                //Log.d(TAG, "index1:_" + split[1] + "_");
                //Log.d(TAG, "index2:_" + split[2] + "_");
                lstFilter.insert(point);
                count++;
            }
            br.close();
        } catch (Exception e){
            e.printStackTrace();
        }
        MultiProfiler.endMark("SI");

        STPoint minBounds, maxBounds;
        float xStep, yStep, tStep;
        if("cabs".equals(dataType)){
//            System.out.println("setting up data type: Cabs");
//            Constants.setCabDefaults();
            float spaceGrid = 10; // 10 km
            float timeGrid = 60 * 60 * 24 * 7; // one week (in seconds)
            STRegion bounds = helper.getBoundingBox();
            minBounds = bounds.getMins();
            maxBounds = bounds.getMaxs();
            STPoint cube = new STPoint(GPSLib.longOffsetFromDistance(minBounds, spaceGrid), GPSLib.latOffsetFromDistance(minBounds, spaceGrid), timeGrid);
            xStep = cube.getX();
            yStep = cube.getY();
            tStep = cube.getT();
        } else {
//            System.out.println("setting up data type: Mobility");
//            Constants.setMobilityDefaults();
            float spaceGrid = 500; // 500m
            float timeGrid = 60 * 60 * 3; // 10 hours (in seconds)
            STRegion bounds = helper.getBoundingBox();
            minBounds = bounds.getMins();
            maxBounds = bounds.getMaxs();
            STPoint cube = new STPoint(spaceGrid, spaceGrid, timeGrid);
            xStep = cube.getX();
            yStep = cube.getY();
            tStep = cube.getT();
        }

        List<Double> poks = new ArrayList<Double>();
        List<Integer> numCandPoints = new ArrayList<Integer>();
        int count = 15;
        MultiProfiler.startMark(stabFunc, null, "Window");
        outerloop:
        for(float x = minBounds.getX(); x < maxBounds.getX(); x+= xStep){
            for(float y = minBounds.getY(); y < maxBounds.getY(); y+= yStep){
                for(float t = minBounds.getT(); t < maxBounds.getT(); t+= tStep) {
                    poks.add(lstFilter.windowPoK(new STRegion(new STPoint(x,y,t), new STPoint(x + xStep,y + yStep,t + tStep))));
                    numCandPoints.add(helper.range(new STRegion(new STPoint(x,y,t), new STPoint(x + xStep,y + yStep,t + tStep))).size());
                    count--;
                    if(count <= 0){ break outerloop; }
                }
            }
        }
        MultiProfiler.endMark("Window");
        MultiProfiler.stopProfiling();

        Log.d(TAG, "Populated Database table with size: " + helper.getSize());
        Log.d(TAG, "Cleared other table with size: " + other.getSize());
        System.out.println(poks);
        System.out.println(numCandPoints);
    }
}
