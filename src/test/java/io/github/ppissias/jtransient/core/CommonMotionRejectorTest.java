package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class CommonMotionRejectorTest {
    @Test
    public void filtersSharedMotionAndKeepsDifferentAndTwoFrameSources() {
        var a = path(100,100, new double[]{0,-0.5,-0.8}, new double[]{0,-0.8,-1.7});
        var b = path(800,600, new double[]{0,-0.4,-0.6}, new double[]{0,-0.9,-1.8});
        var different = path(500,500,new double[]{0,10,20},new double[]{0,0,0});
        var two = path(20,20,new double[]{0,0.2},new double[]{0,3.4});
        var frames = frames(a,b,different,two);
        var groups = CommonMotionRejector.reject(frames,new DetectionConfig());
        assertEquals(1,groups.size());
        assertEquals(2,groups.get(0).sources.size());
        assertEquals(3,groups.get(0).sharedFrameCount);
        var expected = new HashSet<SourceExtractor.DetectedObject>();
        expected.addAll(different); expected.addAll(two);
        assertEquals(expected,new HashSet<>(flat(frames)));
    }

    @Test
    public void followsReversingMotionWithoutSkippingTheMiddleFrame() {
        var frames = frames(path(100,100,new double[]{0,5,1},new double[]{0,0,0}),
                            path(800,600,new double[]{0,5,1},new double[]{0,0,0}));
        var groups = CommonMotionRejector.reject(frames,new DetectionConfig());
        assertEquals(1,groups.size());
        assertEquals(3,groups.get(0).sharedFrameCount);
        assertTrue(flat(frames).isEmpty());
    }

    @Test
    public void keepsAmbiguousConvergingSourcesInsteadOfReusingTheirObservations() {
        var frames = new ArrayList<List<SourceExtractor.DetectedObject>>();
        frames.add(new ArrayList<>(List.of(point(100,100,0),point(120,100,0),point(600,100,0),point(1120,100,0))));
        frames.add(new ArrayList<>(List.of(point(110,100,1),point(610,100,1),point(1110,100,1))));
        frames.add(new ArrayList<>(List.of(point(135,100,2),point(635,100,2),point(1135,100,2))));
        assertTrue(CommonMotionRejector.reject(frames,new DetectionConfig()).isEmpty());
        assertEquals(10,flat(frames).size());
    }

    @Test
    public void commonMotionIsIndependentOfTheResidualAnalysisSwitch() {
        var config=new DetectionConfig();config.enableResidualTransientAnalysis=false;
        var frames=frames(path(100,100,new double[]{0,4,8},new double[]{0,0,0}),
                          path(800,600,new double[]{0,4,8},new double[]{0,0,0}));
        assertEquals(1,CommonMotionRejector.reject(frames,config).size());
        assertTrue(flat(frames).isEmpty());
        var residual=ResidualTransientAnalyzer.analyze(frames,config);
        assertTrue(residual.localRescueCandidates.isEmpty());
        assertTrue(residual.localActivityClusters.isEmpty());
    }

    @Test
    public void buildsTheIndependentActivityClusterFromFilteredLeftovers() {
        var frames=new ArrayList<List<SourceExtractor.DetectedObject>>();
        frames.add(new ArrayList<>(List.of(point(100,100,0),point(800,600,0),point(110,105,0))));
        frames.add(new ArrayList<>(List.of(point(104,100,1),point(804,600,1),point(112,109,1))));
        frames.add(new ArrayList<>(List.of(point(108,100,2),point(808,600,2),point(110,113,2))));
        var config=new DetectionConfig();config.enableLocalRescueCandidates=false;config.enableLocalActivityClusters=true;
        assertEquals(1,CommonMotionRejector.reject(frames,config).size());
        var residual=ResidualTransientAnalyzer.analyze(frames,config);
        assertEquals(1,residual.localActivityClusters.size());
        assertEquals(3,residual.localActivityClusters.get(0).points.size());
        assertEquals(new HashSet<>(flat(frames)),new HashSet<>(residual.localActivityClusters.get(0).points));
    }

    @Test
    public void everyGroupMemberMustShareAtLeastThreeOfTheSameFrames() {
        var a=sparse(100,0,1,2,3);var b=sparse(800,0,1,2,4);var c=sparse(1400,1,2,3,4);
        var frames=frames(a,b,c);
        var groups=CommonMotionRejector.reject(frames,new DetectionConfig());
        assertEquals(1,groups.size());
        assertEquals(2,groups.get(0).sources.size());
        assertEquals(3,groups.get(0).sharedFrameCount);
        assertEquals(new HashSet<>(c),new HashSet<>(flat(frames)));
    }

    @Test
    public void toleratesMissingFramesAndDuplicateInputReferences() {
        var a=sparse(100,0,3,6);var b=sparse(800,0,3,6);
        var frames=frames(a,b);frames.get(0).add(a.get(0));
        var groups=CommonMotionRejector.reject(frames,new DetectionConfig());
        assertEquals(1,groups.size());
        assertEquals(3,groups.get(0).sharedFrameCount);
        assertTrue(flat(frames).isEmpty());
        assertEquals(6,groups.get(0).sources.stream().mapToInt(List::size).sum());
    }

    @Test
    public void leavesTheInputAloneWhenDisabledOrWhenOneSourceIsNotCompact() {
        var config=new DetectionConfig();config.enableCommonMotionRejection=false;
        var a=path(100,100,new double[]{0,4,8},new double[]{0,0,0});
        var b=path(800,600,new double[]{0,4,8},new double[]{0,0,0});
        var frames=frames(a,b);
        assertTrue(CommonMotionRejector.reject(frames,config).isEmpty());
        assertEquals(6,flat(frames).size());
        config.enableCommonMotionRejection=true;b.get(0).elongation=6;
        assertTrue(CommonMotionRejector.reject(frames,config).isEmpty());
        assertEquals(6,flat(frames).size());
    }

    private static List<SourceExtractor.DetectedObject> path(double x,double y,double[] dx,double[] dy) {
        var out=new ArrayList<SourceExtractor.DetectedObject>();
        for(int j=0;j<dx.length;j++)out.add(point(x+dx[j],y+dy[j],j));
        return out;
    }
    private static List<SourceExtractor.DetectedObject> sparse(double x,int... indices) {
        var out=new ArrayList<SourceExtractor.DetectedObject>();
        for(int frame:indices)out.add(point(x,100,frame));return out;
    }
    @SafeVarargs
    private static List<List<SourceExtractor.DetectedObject>> frames(List<SourceExtractor.DetectedObject>... sources) {
        int count=Arrays.stream(sources).flatMap(List::stream).mapToInt(p->p.sourceFrameIndex).max().orElse(-1)+1;
        var out=new ArrayList<List<SourceExtractor.DetectedObject>>();
        for(int j=0;j<count;j++)out.add(new ArrayList<>());
        for(var source:sources)for(var p:source)out.get(p.sourceFrameIndex).add(p);
        return out;
    }
    private static List<SourceExtractor.DetectedObject> flat(List<List<SourceExtractor.DetectedObject>> frames) {
        return frames.stream().flatMap(List::stream).toList();
    }
    private static SourceExtractor.DetectedObject point(double x,double y,int frame) {
        var p=new SourceExtractor.DetectedObject(x,y,8,8);p.pixelArea=8;p.elongation=1.3;p.peakSigma=6;p.sourceFrameIndex=frame;return p;
    }
}