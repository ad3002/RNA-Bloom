/* 
 * Copyright (C) 2018 BC Cancer Genome Sciences Centre
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package rnabloom;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import static java.lang.Math.pow;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import rnabloom.bloom.BloomFilter;
import rnabloom.bloom.CountingBloomFilter;
import rnabloom.bloom.PairedKeysBloomFilter;
import rnabloom.bloom.hash.NTHashIterator;
import rnabloom.bloom.hash.PairedNTHashIterator;
import rnabloom.graph.BloomFilterDeBruijnGraph;
import rnabloom.graph.Kmer;
import static rnabloom.io.Constants.FASTA_EXT;
import rnabloom.io.FastaFilteredSequenceIterator;
import rnabloom.io.FastaReader;
import rnabloom.io.FastaWriter;
import rnabloom.io.FastqFilteredSequenceIterator;
import rnabloom.io.FastxFilePair;
import rnabloom.io.PairedReadSegments;
import rnabloom.io.FastqReader;
import rnabloom.io.FastqRecord;
import rnabloom.io.FastxPairSequenceIterator;
import rnabloom.io.FastxSequenceIterator;
import rnabloom.io.FileFormatException;
import rnabloom.io.NucleotideBitsReader;
import rnabloom.io.NucleotideBitsWriter;
import static rnabloom.olc.OverlapLayoutConcensus.hasMinimap2;
import static rnabloom.olc.OverlapLayoutConcensus.hasRacon;
import static rnabloom.olc.OverlapLayoutConcensus.overlapLayout;
import static rnabloom.olc.OverlapLayoutConcensus.overlapLayoutConcensus;
import rnabloom.util.BitSequence;
import rnabloom.util.GraphUtils;
import static rnabloom.util.GraphUtils.*;
import rnabloom.util.NTCardHistogram;
import static rnabloom.util.SeqUtils.*;
import static rnabloom.io.Constants.NBITS_EXT;
import rnabloom.io.SequenceFileIteratorInterface;
import static rnabloom.olc.OverlapLayoutConcensus.clusteredOLC;
import static rnabloom.olc.OverlapLayoutConcensus.mapAndConcensus;

/**
 *
 * @author Ka Ming Nip
 */
public class RNABloom {
    public final static String VERSION = "1.4.0-r2021-02-15a";
    
//    private final static long NUM_PARSED_INTERVAL = 100000;
    public final static long NUM_BITS_1GB = (long) pow(1024, 3) * 8;
    public final static long NUM_BYTES_1GB = (long) pow(1024, 3);
    public final static long NUM_BYTES_1MB = (long) pow(1024, 2);
    public final static long NUM_BYTES_1KB = (long) 1024;
        
    private boolean debug = false;
    private int k;
    private boolean strandSpecific;
    private Pattern seqPattern;
    private Pattern qualPatternDBG;
    private Pattern qualPatternFrag;
    private Pattern polyATailPattern;
    private Pattern polyATailOnlyPattern;
    private Pattern polyATailOnlyMatchingPattern;
    private Pattern polyTHeadOnlyPattern;
    private Pattern polyASignalPattern;
    private BloomFilterDeBruijnGraph graph = null;
    private BloomFilter screeningBf = null;

    private int maxTipLength;
    private int lookahead;
    private float maxCovGradient;
    private int maxIndelSize;
    private int minPolyATailLengthRequired;
    private float percentIdentity;
    private int minNumKmerPairs;
    private int longFragmentLengthThreshold = -1;
    
    private int qDBG = -1;
    private int qFrag = -1;
    
    private float dbgFPR = -1;
    private float covFPR = -1;
    
    private final static String STRATUM_01 = "01";
    private final static String STRATUM_E0 = "e0";
    private final static String STRATUM_E1 = "e1";
    private final static String STRATUM_E2 = "e2";
    private final static String STRATUM_E3 = "e3";
    private final static String STRATUM_E4 = "e4";
    private final static String STRATUM_E5 = "e5";
    private final static String[] STRATA = new String[]{STRATUM_01, STRATUM_E0, STRATUM_E1, STRATUM_E2, STRATUM_E3, STRATUM_E4, STRATUM_E5};
    private final static String[] COVERAGE_ORDER = {STRATUM_E0, STRATUM_E1, STRATUM_E2, STRATUM_E3, STRATUM_E4, STRATUM_E5};
    private final static int[] COVERAGE_ORDER_VALUES = new int[]{1,10,100,1000,10000,10000};
    
    private static boolean isValidStratumName(String name) {
        switch (name) {
            case(STRATUM_01):
            case(STRATUM_E0):
            case(STRATUM_E1):
            case(STRATUM_E2):
            case(STRATUM_E3):
            case(STRATUM_E4):
            case(STRATUM_E5):
                return true;
            default:
                return false;
        }
    }
    
    private static int getStratumIndex(String name) {
        int index = -1;
        int numStrata = STRATA.length;
        
        for (int i=0; i<numStrata; ++i) {
            String s = STRATA[i];
            if (s.equals(name)) {
                return i;
            }
        }
        
        return index;
    }
    
    private static boolean isLowerStratum(String name1, String name2) {
        return getStratumIndex(name1) < getStratumIndex(name2);
    }
    
    private static boolean isHigherStratum(String name1, String name2) {
        return getStratumIndex(name1) > getStratumIndex(name2);
    }
    
    public RNABloom(int k, int qDBG, int qFrag, boolean debug) {
        this.qDBG = qDBG;
        this.qFrag = qFrag;
        this.debug = debug;
        this.setK(k);
    }
    
    public final void setK(int k) {
        this.k = k;
        
        this.seqPattern = getNucleotideCharsPattern(k);
        this.qualPatternDBG = getPhred33Pattern(qDBG, k);
        this.qualPatternFrag = getPhred33Pattern(qFrag, k);
        
        if (graph != null) {
            graph.setK(k);
        }
    }
    
    public void setParams(boolean strandSpecific,
            int maxTipLength, 
            int lookahead, 
            float maxCovGradient, 
            int maxIndelSize, 
            float percentIdentity, 
            int minNumKmerPairs,
            int minPolyATail) {
        
        this.strandSpecific = strandSpecific;
        this.maxTipLength = maxTipLength;
        this.lookahead = lookahead;
        this.maxCovGradient = maxCovGradient;
        this.maxIndelSize = maxIndelSize;
        this.percentIdentity = percentIdentity;
        this.minNumKmerPairs = minNumKmerPairs;
        this.minPolyATailLengthRequired = minPolyATail;
        
        if (minPolyATail > 0) {
            polyATailOnlyPattern = getPolyATailPattern(minPolyATail);
            polyTHeadOnlyPattern = getPolyTHeadPattern(minPolyATail);
            
            polyASignalPattern = getPolyASignalPattern();
            polyATailOnlyMatchingPattern = getPolyATailMatchingPattern(minPolyATail);
            
            if (strandSpecific) {
                polyATailPattern = polyATailOnlyPattern;
            }
            else {
                polyATailPattern = getPolyTHeadOrPolyATailPattern(minPolyATail);
            }
        }
    }
    
    private static void exitOnError(String msg) {
        System.out.println("ERROR: " + msg);
        System.exit(1);
    }
    
    private static void handleException(Exception ex) {
        System.out.println("ERROR: " + ex.getMessage() );
        ex.printStackTrace();
        System.exit(1);
    }
    
    public void saveGraph(File f) throws IOException {
        graph.save(f);
    }
    
    public void restoreGraph(File f, boolean loadDbgBits) throws IOException {
        if (graph != null) {
            graph.destroy();
        }

        graph = new BloomFilterDeBruijnGraph(f, loadDbgBits);

        if (loadDbgBits) {
            dbgFPR = graph.getDbgbfFPR();
            System.out.println("DBG Bloom filter FPR:                " + dbgFPR * 100 + " %");
        }

        covFPR = graph.getCbfFPR();
        System.out.println("Counting Bloom filter FPR:           " + covFPR * 100 + " %");
        
        PairedKeysBloomFilter rpkbf = graph.getRpkbf();
        if (rpkbf != null) {
            System.out.println("Read paired k-mers Bloom filter FPR: " + rpkbf.getFPR() * 100 + " %");
        }
    }
    
    public boolean isGraphInitialized() {
        return graph != null;
    }
    
    public void clearDbgBf() {
        graph.clearDbgbf();
        dbgFPR = 0;
    }

    public void clearCBf() {
        graph.clearCbf();
        covFPR = 0;
    }
    
    public void clearSBf() {
        if (screeningBf != null) {
            screeningBf.empty();
        }
    }
    
    public void clearPkBf() {
        graph.clearFpkbf();
    }
    
    public void clearRpkBf() {
        graph.clearRpkbf();
    }
    
    public void clearAllBf() {
        graph.clearAllBf();
        
        dbgFPR = 0;
        covFPR = 0;
    }

    public void destroyAllBf() {
        if (graph != null) {
            graph.destroy();
        }
        
        if (screeningBf != null) {
            screeningBf.destroy();
            screeningBf = null;
        }
        
        dbgFPR = 0;
        covFPR = 0;
    }
    
    public static void checkInputFileFormat(String[] paths) throws FileFormatException {
        for (String p : paths) {
            if (!FastaReader.isCorrectFormat(p) && !FastqReader.isCorrectFormat(p)) {
                throw new FileFormatException("Unsupported file format detected in input file `" + p + "`. Only FASTA and FASTQ formats are supported.");
            }
        }
    }
    
    public class PairedKmersToGraphWorker implements Runnable {
        private final int id;
        private final String path;
        private PairedNTHashIterator pitr = null;
        private long numReads = 0;
        private boolean successful = false;
        private boolean existingKmersOnly = false;
        
        public PairedKmersToGraphWorker(int id, String path, boolean existingKmersOnly) {
            this.id = id;
            this.path = path;
            this.existingKmersOnly = existingKmersOnly;
            this.pitr = graph.getPairedHashIterator(graph.getReadPairedKmerDistance());
        }
        
        @Override
        public void run() {
            System.out.println("[" + id + "] Parsing `" + path + "`...");
            
            try {
                Matcher mSeq = seqPattern.matcher("");

                if (FastaReader.isCorrectFormat(path)) {
                    FastaReader fr = new FastaReader(path);

                    String seq;

                    long[] lHashVals = pitr.hVals1;
                    long[] rHashVals = pitr.hVals2;
                    long[] pHashVals = pitr.hVals3;

                    if (existingKmersOnly) {
                        while (fr.hasNext()) {
                            seq = fr.next();
                            mSeq.reset(seq);

                            while (mSeq.find()) {
                                if (pitr.start(seq, mSeq.start(), mSeq.end())) {
                                    while (pitr.hasNext()) {
                                        pitr.next();
                                        if (graph.contains(lHashVals) && graph.contains(rHashVals)) {
                                            graph.addReadSingleKmerPair(pHashVals);
                                        }
                                    }
                                }
                            }

                            ++numReads;
                        }
                    }
                    else {
                        while (fr.hasNext()) {
                            seq = fr.next();
                            mSeq.reset(seq);

                            while (mSeq.find()) {
                                if (pitr.start(seq, mSeq.start(), mSeq.end())) {
                                    while (pitr.hasNext()) {
                                        pitr.next();
                                        graph.addReadSingleKmerPair(pHashVals);
                                    }
                                }
                            }

                            ++numReads;
                        }
                    }
                    
                    fr.close();
                }
                else {
                    throw new RuntimeException("Unsupported file format detected in input file `" + path + "`. Only FASTA and FASTQ formats are supported.");
                }
                
                successful = true;
                System.out.println("[" + id + "] Parsed " + NumberFormat.getInstance().format(numReads) + " sequences.");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        
        public boolean isSuccessful() {
            return successful;
        }
        
        public long getReadCount() {
            return numReads;
        }
    }
    
    public class SeqToGraphWorker implements Runnable {
        private final int id;
        private final String path;
        private NTHashIterator itr;
        private PairedNTHashIterator pitr = null;
        private long numReads = 0;
        private boolean successful = false;
        private final Consumer<long[]> addFunction;
        private boolean storeReadPairedKmers = false;
        
        public SeqToGraphWorker(int id, String path, boolean reverseComplement, boolean incrementIfPresent, boolean storeReadPairedKmers) {            
            this.id = id;
            this.path = path;
            this.storeReadPairedKmers = storeReadPairedKmers;
            
            int numHash = graph.getMaxNumHash();
            this.itr = reverseComplement ? graph.getReverseComplementHashIterator(numHash) : graph.getHashIterator(numHash);
            
            if (storeReadPairedKmers) {
                int kmerPairDistance = graph.getReadPairedKmerDistance();
                this.pitr = reverseComplement ? graph.getReverseComplementPairedHashIterator(kmerPairDistance) : graph.getPairedHashIterator(kmerPairDistance);
            }
            
            this.addFunction = incrementIfPresent ? graph::addCountIfPresent : graph::add;
        }
                
        @Override
        public void run() {
            System.out.println("[" + id + "] Parsing `" + path + "`...");
            
            try {
                Matcher mSeq = seqPattern.matcher("");
                long[] hashVals = itr.hVals;
                
                if (FastqReader.isCorrectFormat(path)) {
                    FastqReader fr = new FastqReader(path);
                    Matcher mQual = qualPatternDBG.matcher("");

                    FastqRecord record = new FastqRecord();

                    if (storeReadPairedKmers) {
                        long[] phashVals = pitr.hVals3;

                        while (fr.hasNext()) {
                            fr.nextWithoutName(record);
                            mQual.reset(record.qual);
                            mSeq.reset(record.seq);

                            while (mQual.find()) {
                                mSeq.region(mQual.start(), mQual.end());
                                while (mSeq.find()) {
                                    int start = mSeq.start();
                                    int end = mSeq.end();

                                    if (itr.start(record.seq, start, end)) {
                                        while (itr.hasNext()) {
                                            itr.next();
                                            addFunction.accept(hashVals);
                                        }

                                        if (pitr.start(record.seq, start, end)) {
                                            while (pitr.hasNext()) {
                                                pitr.next();
                                                graph.addReadSingleKmerPair(phashVals);
                                            }
                                        }
                                    }
                                }
                            }

                            ++numReads;
                        }
                    }
                    else {
                        while (fr.hasNext()) {
                            fr.nextWithoutName(record);
                            mQual.reset(record.qual);
                            mSeq.reset(record.seq);

                            while (mQual.find()) {
                                mSeq.region(mQual.start(), mQual.end());
                                while (mSeq.find()) {
                                    if (itr.start(record.seq, mSeq.start(), mSeq.end())) {
                                        while (itr.hasNext()) {
                                            itr.next();
                                            addFunction.accept(hashVals);
                                        }
                                    }
                                }
                            }

                            ++numReads;
                        }
                    }
                    
                    fr.close();
                }
                else if (FastaReader.isCorrectFormat(path)) {
                    FastaReader fr = new FastaReader(path);

                    String seq;

                    if (storeReadPairedKmers) {
                        long[] phashVals = pitr.hVals3;

                        while (fr.hasNext()) {
                            seq = fr.next();
                            mSeq.reset(seq);

                            while (mSeq.find()) {
                                int start = mSeq.start();
                                int end = mSeq.end();

                                if (itr.start(seq, start, end)) {
                                    while (itr.hasNext()) {
                                        itr.next();
                                        addFunction.accept(hashVals);
                                    }

                                    if (pitr.start(seq, start, end)) {
                                        while (pitr.hasNext()) {
                                            pitr.next();
                                            graph.addReadSingleKmerPair(phashVals);
                                        }
                                    }
                                }
                            }

                            ++numReads;
                        }
                    }
                    else {
                        while (fr.hasNext()) {
                            seq = fr.next();
                            mSeq.reset(seq);

                            while (mSeq.find()) {
                                if (itr.start(seq, mSeq.start(), mSeq.end())) {
                                    while (itr.hasNext()) {
                                        itr.next();
                                        addFunction.accept(hashVals);
                                    }
                                }
                            }

                            ++numReads;
                        }
                    }
                    
                    fr.close();
                }
                else {
                    throw new RuntimeException("Unsupported file format detected in input file `" + path + "`. Only FASTA and FASTQ formats are supported.");
                }
                
                successful = true;
                System.out.println("[" + id + "] Parsed " + NumberFormat.getInstance().format(numReads) + " sequences.");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        
        public boolean isSuccessful() {
            return successful;
        }
        
        public long getReadCount() {
            return numReads;
        }
    }
        
    public int getMaxReadLength(String path) throws FileFormatException, IOException{
        int max = -1;

        if (FastqReader.isCorrectFormat(path)) {
            FastqRecord r = new FastqRecord();
            FastqReader fr = new FastqReader(path);
            for (int i=0; i< 100 && fr.hasNext(); ++i) {
                fr.nextWithoutName(r);
                max = Math.max(max, r.seq.length());
            }
            fr.close();
        }
        else if (FastaReader.isCorrectFormat(path)) {
            FastaReader fr = new FastaReader(path);
            for (int i=0; i< 100 && fr.hasNext(); ++i) {
                max = Math.max(max, fr.next().length());
            }
            fr.close();                
        }
        else {
            throw new FileFormatException("Incompatible file format for `" + path + "`");
        }
        
        return max;
    }
    
    public void initializeGraph(boolean strandSpecific,
                            long dbgbfNumBits,
                            long cbfNumBytes,
                            long pkbfNumBits,
                            int dbgbfNumHash,
                            int cbfNumHash,
                            int pkbfNumHash,
                            boolean initPkbf,
                            boolean useReadPairedKmers) {
        
        graph = new BloomFilterDeBruijnGraph(dbgbfNumBits,
                                            cbfNumBytes,
                                            pkbfNumBits,
                                            dbgbfNumHash,
                                            cbfNumHash,
                                            pkbfNumHash,
                                            k,
                                            strandSpecific,
                                            useReadPairedKmers);
        
        if (initPkbf) {
            graph.initializePairKmersBloomFilter(pkbfNumBits, pkbfNumHash);
        }
    }
        
    public void setReadKmerDistance(Collection<String> forwardReadPaths,
                                    Collection<String> reverseReadPaths) throws IOException {
        
        int readLength = -1;
        
        for (String path : forwardReadPaths) {
            int len = this.getMaxReadLength(path);
            if (len > 0) {
                if (readLength < 0) {
                    readLength = len;
                }
                else {
                    readLength = Math.min(readLength, len);
                }
            }
            else {
                exitOnError("Cannot determine read length from read files.");
            }
        }
        
        for (String path : reverseReadPaths) {
            int len = this.getMaxReadLength(path);
            if (len > 0) {
                if (readLength < 0) {
                    readLength = len;
                }
                else {
                    readLength = Math.min(readLength, len);
                }
            }
            else {
                exitOnError("Cannot determine read length from read files.");
            }
        }
        
        if (readLength < 0) {
            exitOnError("Cannot determine read length from read files.");
        }

        if (readLength < k) {
            exitOnError("The read length (" + readLength + ") is too short for k-mer size (" + k + ").");
        }
        
        System.out.println("Read length: " + readLength);
        
        /*
            |<--d-->|
            ==------==     paired k-mers
             ==------==    
              ==------==   
               ==------==  
                ==------== 
            ============== single read
        */
        
        graph.setReadPairedKmerDistance(readLength - k - minNumKmerPairs);
    }
    
    public int getReadLength() {
        int d = graph.getReadPairedKmerDistance();
        return d <= 0 ? d : d + k + minNumKmerPairs;
    }
    
    public void populateGraph(Collection<String> forwardReadPaths,
                            Collection<String> reverseReadPaths,
                            Collection<String> longReadPaths,
                            Collection<String> refTranscriptsPaths,
                            boolean strandSpecific,
                            boolean reverseComplementLong,
                            int numThreads,
                            boolean addCountsOnly,
                            boolean storeReadKmerPairs) throws IOException, InterruptedException {        
        
//        screeningBf = new BloomFilter(sbfNumBits, sbfNumHash, graph.getHashFunction());

        if (storeReadKmerPairs) {
            setReadKmerDistance(forwardReadPaths, reverseReadPaths);
        }

        /** parse the reads */
        
        long numReads = 0;
        
        ExecutorService service = Executors.newFixedThreadPool(numThreads);
        
        ArrayList<SeqToGraphWorker> threadPool = new ArrayList<>();
        int threadId = 0;
           
        for (String path : forwardReadPaths) {
            SeqToGraphWorker t = new SeqToGraphWorker(++threadId, path, false, addCountsOnly, storeReadKmerPairs);
            service.submit(t);
            threadPool.add(t);
        }

        for (String path : reverseReadPaths) {
            SeqToGraphWorker t = new SeqToGraphWorker(++threadId, path, true, addCountsOnly, storeReadKmerPairs);
            service.submit(t);
            threadPool.add(t);
        }
        
        for (String path : longReadPaths) {
            SeqToGraphWorker t = new SeqToGraphWorker(++threadId, path, reverseComplementLong, addCountsOnly, false);
            service.submit(t);
            threadPool.add(t);
        }

        service.shutdown();
        service.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        
        for (SeqToGraphWorker t : threadPool) {
            numReads += t.getReadCount();
        }

        System.out.println("Parsed " + NumberFormat.getInstance().format(numReads) + " reads in total.");
        
        if (!refTranscriptsPaths.isEmpty()) {
            System.out.println("Augmenting graph with reference transcripts...");

            service = Executors.newFixedThreadPool(numThreads);

            for (String path : refTranscriptsPaths) {
                PairedKmersToGraphWorker t = new PairedKmersToGraphWorker(++threadId, path, true);
                service.submit(t);
            }

            service.shutdown();
            service.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
        
        dbgFPR = graph.getDbgbf().getFPR();
        covFPR = graph.getCbf().getFPR();
        
        System.out.println(    "DBG Bloom filter FPR:                 " + dbgFPR * 100 + " %");
        System.out.println(    "Counting Bloom filter FPR:            " + covFPR * 100 + " %");
        
        if (graph.getReadPairedKmerDistance() > 0) {
            System.out.println("Reads paired k-mers Bloom filter FPR: " + graph.getRpkbf().getFPR() * 100 + " %");
        }
//        System.out.println("Screening Bloom filter FPR:  " + screeningBf.getFPR() * 100 + " %");
    }
    
    public boolean withinMaxFPR(float fpr) {
        float maxFPR = fpr * 2f;
        return (graph.getDbgbf() == null || graph.getDbgbfFPR() <= maxFPR) && 
                (graph.getCbf() == null || graph.getCbfFPR() <= maxFPR) && 
                (graph.getRpkbf() == null || graph.getRpkbfFPR() <= maxFPR);
    }
    
    public long[] getOptimalBloomFilterSizes(float maxFPR, int sbfNumHash, int dbgbfNumHash, int cbfNumHash, int pkbfNumHash) {
        long maxPopCount = 0;
        if (screeningBf != null) {
            maxPopCount = Math.max(maxPopCount, screeningBf.getPopCount());
        }
        
        if (graph != null) {
            if (graph.getDbgbf() != null) {
                maxPopCount = Math.max(maxPopCount, graph.getDbgbf().getPopCount());
            }

            if (graph.getCbf() != null) {
                maxPopCount = Math.max(maxPopCount, graph.getCbf().getPopCount());
            }

            if (graph.getRpkbf() != null) {
                maxPopCount = Math.max(maxPopCount, graph.getRpkbf().getPopCount());
            }

            if (graph.getFpkbf() != null) {
                maxPopCount = Math.max(maxPopCount, graph.getFpkbf().getPopCount());
            }
        }
        
        long sbfSize = BloomFilter.getExpectedSize(maxPopCount, maxFPR, sbfNumHash);
        long dbgbfSize = BloomFilter.getExpectedSize(maxPopCount, maxFPR, dbgbfNumHash);
        long cbfSize = CountingBloomFilter.getExpectedSize(maxPopCount, maxFPR, cbfNumHash);
        long pkbfSize = PairedKeysBloomFilter.getExpectedSize(maxPopCount, maxFPR, pkbfNumHash);
                
        return new long[]{sbfSize, dbgbfSize, cbfSize, pkbfSize};
    }
    
    public void addPairedKmersFromSequences(String[] fastas, boolean existingKmersOnly) throws IOException {
        PairedNTHashIterator readItr = graph.getPairedHashIterator(graph.getReadPairedKmerDistance());
        long[] readHashValsL = readItr.hVals1;
        long[] readHashValsR = readItr.hVals2;
        long[] readHashValsP = readItr.hVals3;        
        
        PairedNTHashIterator fragItr = graph.getPairedHashIterator(graph.getFragPairedKmerDistance());
        long[] fragHashValsL = fragItr.hVals1;
        long[] fragHashValsR = fragItr.hVals2;
        long[] fragHashValsP = fragItr.hVals3;

        if (existingKmersOnly) {
            for (String path : fastas) {
                FastaReader fin = new FastaReader(path);

                System.out.println("Parsing `" + path + "`...");

                String seq;
                while (fin.hasNext()) {
                    seq = fin.next();

                    if (readItr.start(seq)) {
                        while (readItr.hasNext()) {
                            readItr.next();
                            if (graph.contains(readHashValsL) && graph.contains(readHashValsR)) {
                                graph.addReadSingleKmerPair(readHashValsP);
                            }
                        }
                        
                        if (fragItr.start(seq)) {
                            while (fragItr.hasNext()) {
                                fragItr.next();
                                if (graph.contains(fragHashValsL) && graph.contains(fragHashValsR)) {
                                    graph.addFragmentSingleKmerPair(fragHashValsP);
                                }
                            }
                        }
                    }
                }

                fin.close();
            }
        }
        else {
            for (String path : fastas) {
                FastaReader fin = new FastaReader(path);

                System.out.println("Parsing `" + path + "`...");

                String seq;
                while (fin.hasNext()) {
                    seq = fin.next();

                    if (readItr.start(seq)) {
                        while (readItr.hasNext()) {
                            readItr.next();
                            graph.addReadSingleKmerPair(readHashValsP);
                        }
                    }
                    
                    if (fragItr.start(seq)) {
                        while (fragItr.hasNext()) {
                            fragItr.next();
                            graph.addFragmentSingleKmerPair(fragHashValsP);
                        }
                    }
                }

                fin.close();
            }
        }
        
        System.out.println("Reads paired kmers Bloom filter FPR:      " + graph.getRpkbfFPR() * 100 + " %");
        System.out.println("Fragments paired kmers Bloom filter FPR:  " + graph.getPkbfFPR() * 100 + " %");
    }
    
    public class FragmentsToGraphWorker implements Runnable {
        private final int id;
        private final String path;
        private final boolean loadPairedKmers;
        private long numSeqs = 0;
        
        public FragmentsToGraphWorker(int id, String path, boolean loadPairedKmers) {
            this.id= id;
            this.path = path;
            this.loadPairedKmers = loadPairedKmers;
        }
        
        @Override
        public void run() {
            try {
                System.out.println("[" + id + "] Parsing `" + path + "`...");
                
                NTHashIterator itr = graph.getHashIterator(graph.getMaxNumHash());
                long[] hashVals = itr.hVals;

                PairedNTHashIterator readItr = graph.getPairedHashIterator(graph.getReadPairedKmerDistance());
                long[] readHashValsP = readItr.hVals3;

                PairedNTHashIterator fragItr = graph.getPairedHashIterator(graph.getFragPairedKmerDistance());
                long[] fragHashValsP = fragItr.hVals3;

                NucleotideBitsReader fin = new NucleotideBitsReader(path);

                if (loadPairedKmers) {
                    String seq = null;
                    while ((seq = fin.next()) != null) {
                        ++numSeqs;

                        if (itr.start(seq)) {
                            while (itr.hasNext()) {
                                itr.next();
                                graph.addDbgOnly(hashVals);
                            }

                            if (readItr.start(seq)) {
                                while (readItr.hasNext()) {
                                    readItr.next();
                                    graph.addReadSingleKmerPair(readHashValsP);
                                }

                                if (fragItr.start(seq)) {
                                    while (fragItr.hasNext()) {
                                        fragItr.next();
                                        graph.addFragmentSingleKmerPair(fragHashValsP);
                                    }
                                }
                            }
                        }
                    }
                }
                else {
                    String seq = null;
                    while ((seq = fin.next()) != null) {
                        ++numSeqs;

                        if (itr.start(seq)) {
                            while (itr.hasNext()) {
                                itr.next();
                                graph.addDbgOnly(hashVals);
                            }
                        }
                    }
                }

                fin.close();
                System.out.println("[" + id + "] Parsed " + NumberFormat.getInstance().format(numSeqs) + " sequences.");
            }
            catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }
    
    public void populateGraphFromFragments(Collection<String> fastas, Collection<String> refFastas, boolean loadPairedKmers, int numThreads) throws InterruptedException {        
        ExecutorService service = Executors.newFixedThreadPool(numThreads);
        
        int threadId = 0;
        
        for (String path : fastas) {
            service.submit(new FragmentsToGraphWorker(++threadId, path, loadPairedKmers));
        }
                
        service.shutdown();
        service.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        
        if (!refFastas.isEmpty()) {
            System.out.println("Augmenting graph with reference transcripts...");
            
            service = Executors.newFixedThreadPool(numThreads);
            
            for (String path : refFastas) {
                service.submit(new PairedKmersToGraphWorker(++threadId, path, true));
            }
            
            service.shutdown();
            service.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
        
        dbgFPR = graph.getDbgbfFPR();

        System.out.println("DBG Bloom filter FPR:                     " + dbgFPR * 100 + " %");
        System.out.println("Reads paired k-mers Bloom filter FPR:     " + graph.getRpkbf().getFPR() * 100 + " %");
        System.out.println("Fragments paired k-mers Bloom filter FPR: " + graph.getFpkbf().getFPR() * 100 + " %");
//        covFPR = graph.getCbfFPR();
    }
        
    public static class ReadPair {
        ArrayList<Kmer> leftKmers;
        ArrayList<Kmer> rightKmers;
        boolean corrected = false;
        
        public ReadPair(ArrayList<Kmer> leftKmers, ArrayList<Kmer> rightKmers, boolean corrected) {
            this.leftKmers = leftKmers;
            this.rightKmers = rightKmers;
            this.corrected = corrected;
        }
    }
    
    private class Fragment {
        String left;
        String right;
        ArrayList<Kmer> kmers;
        int length;
        float minCov;
        boolean isUnconnectedRead;
        
        public Fragment(String left, String right, ArrayList<Kmer> kmers, int length, float minCov, boolean isUnconnectedRead) {
            this.left = left;
            this.right = right;
            this.kmers = kmers;
            this.length = length;
            this.minCov = minCov;
            this.isUnconnectedRead = isUnconnectedRead;
        }
    }
    
    private class Transcript {
        String fragment;
        ArrayList<Kmer> transcriptKmers;
        
        public Transcript(String fragment, ArrayList<Kmer> transcriptKmers) {
            this.fragment = fragment;
            this.transcriptKmers = transcriptKmers;
        }
    }
    
    private class TranscriptWriter {
        private final FastaWriter fout;
        private final FastaWriter foutShort;
        private final int minTranscriptLength;
        private final int maxTipLength;
        private final boolean writeUracil;
        private String prefix = "";
        private long cid = 0;
        
        public TranscriptWriter(FastaWriter fout,
                                FastaWriter foutShort,
                                int minTranscriptLength,
                                int maxTipLength,
                                boolean writeUracil) {
            this.fout = fout;
            this.foutShort = foutShort;
            this.minTranscriptLength = minTranscriptLength;
            this.maxTipLength = maxTipLength;
            this.writeUracil = writeUracil;
        }
        
        public void setOutputPrefix(String prefix) {
            this.prefix = prefix;
        }
                
        public synchronized void write(String fragment, ArrayList<Kmer> transcriptKmers) throws IOException {            
            if (!represented(transcriptKmers,
                                graph,
                                screeningBf,
                                lookahead,
                                maxIndelSize,
                                maxTipLength,
                                percentIdentity)) {

                String transcript = graph.assemble(transcriptKmers);
                ArrayDeque<Integer> pasPositions = null;
                
                boolean txptReverseComplemented = false;
                if (minPolyATailLengthRequired > 0) {
                    boolean hasPolyATail = polyATailOnlyPattern.matcher(transcript).matches();
                    boolean hasPolyTHead = false;
                    
                    if (strandSpecific) {
                        pasPositions = getPolyASignalPositions(transcript, polyASignalPattern, polyATailOnlyMatchingPattern);
                    }
                    else {
                        hasPolyTHead = polyTHeadOnlyPattern.matcher(transcript).matches();
                        
                        if (hasPolyATail) {
                            pasPositions = getPolyASignalPositions(transcript, polyASignalPattern, polyATailOnlyMatchingPattern);
                        }
                        
                        if (hasPolyTHead && (pasPositions == null || pasPositions.isEmpty())) {
                            String transcriptRC = reverseComplement(transcript);
                            
                            pasPositions = getPolyASignalPositions(transcriptRC, polyASignalPattern, polyATailOnlyMatchingPattern);
                            
                            if (!pasPositions.isEmpty()) {
                                transcript = transcriptRC;
                                txptReverseComplemented = true;
                            }
                        }
                    }
                }
                                
                for (Kmer kmer : transcriptKmers) {
                    screeningBf.add(kmer.getHash());
                }
                
                int len = transcript.length();
                float cov = getMedianKmerCoverage(transcriptKmers);
                
                StringBuilder headerBuilder = new StringBuilder();
                headerBuilder.append(prefix);
                headerBuilder.append(++cid);
                headerBuilder.append(" l=");
                headerBuilder.append(len);
                headerBuilder.append(" c=");
                headerBuilder.append(String.format("%.1f", cov));
                if (!fragment.isEmpty()) {
                    headerBuilder.append(" F=[");
                    headerBuilder.append(fragment);
                    headerBuilder.append("]");
                }
                
                if (minPolyATailLengthRequired > 0) {                    
                    if (pasPositions != null && !pasPositions.isEmpty()) {
                        // add PAS and their positions to header
                        headerBuilder.append(" PAS=[");
                        
                        Iterator<Integer> itr = pasPositions.iterator();

                        int pasPos = itr.next();
                        int numTanscriptKmers = transcriptKmers.size();

                        headerBuilder.append(pasPos);
                        headerBuilder.append(':');
                        
                        if (txptReverseComplemented) {
                            // `transcriptKmers` has a poly-T-head
                            
                            headerBuilder.append(GraphUtils.getMinimumKmerCoverage(transcriptKmers, 0, Math.min(numTanscriptKmers-1, numTanscriptKmers-1-pasPos-6+k)));
                            headerBuilder.append(':');
                            headerBuilder.append(transcript.substring(pasPos, pasPos+6));

                            while (itr.hasNext()) {
                                headerBuilder.append(",");

                                pasPos = itr.next();

                                headerBuilder.append(pasPos);
                                headerBuilder.append(':');
                                headerBuilder.append(GraphUtils.getMinimumKmerCoverage(transcriptKmers, 0, Math.min(numTanscriptKmers-1, numTanscriptKmers-1-pasPos-6+k)));
                                headerBuilder.append(':');
                                headerBuilder.append(transcript.substring(pasPos, pasPos+6));
                            }                               
                        }
                        else {
                            // `transcriptKmers` has a poly-A-tail
                            
                            headerBuilder.append(GraphUtils.getMinimumKmerCoverage(transcriptKmers, Math.max(0, pasPos+6-k), numTanscriptKmers-1));
                            headerBuilder.append(':');
                            headerBuilder.append(transcript.substring(pasPos, pasPos+6));

                            while (itr.hasNext()) {
                                headerBuilder.append(",");

                                pasPos = itr.next();

                                headerBuilder.append(pasPos);
                                headerBuilder.append(':');
                                headerBuilder.append(GraphUtils.getMinimumKmerCoverage(transcriptKmers, Math.max(0, pasPos+6-k), numTanscriptKmers-1));
                                headerBuilder.append(':');
                                headerBuilder.append(transcript.substring(pasPos, pasPos+6));
                            }    
                        }
                        
                        // mask PAS in transcript
                        StringBuilder transcriptSB = new StringBuilder(transcript);

                        for (int p : pasPositions) {
                            for (int i=0; i<6; ++i) {
                                char c = transcript.charAt(p+i);
                                if (writeUracil && c=='T') {
                                    transcriptSB.setCharAt(p+i, 'u');
                                }
                                else {
                                    transcriptSB.setCharAt(p+i, Character.toLowerCase(c));
                                }
                            }
                        }
                        
                        transcript = transcriptSB.toString();
                        
                        headerBuilder.append("]");
                    }
                }
                
                if (writeUracil) {
                    transcript = transcript.replace('T', 'U');
                }
                
                if (len >= minTranscriptLength) {
                    fout.write(headerBuilder.toString(), transcript);
                }
                else {
                    foutShort.write(headerBuilder.toString(), transcript);
                }
            }
        }
    }
    
    private static int intervalOverlapSize(int[] x, int[] y) {
        int start = Math.max(x[0], y[0]);
        int end = Math.min(x[1], y[1]);
        
        return Math.max(0, end-start);
    }
    
    private class TranscriptAssemblyWorker implements Runnable {
        private TranscriptWriter writer;
        private final SequenceFileIteratorInterface fin;
        private boolean extendBranchFreeFragmentsOnly = false;
        private boolean keepArtifact = false;
        private boolean keepChimera = false;
        private boolean haveFragKmers = false;
        private final float minKmerCov;
        private long numParsed;
        
        public TranscriptAssemblyWorker(SequenceFileIteratorInterface fin,
                                        TranscriptWriter writer,
                                        boolean includeNaiveExtensions,
                                        boolean extendBranchFreeFragmentsOnly,
                                        boolean keepArtifact,
                                        boolean keepChimera,
                                        boolean haveFragKmers,
                                        float minKmerCov) {
            this.fin = fin;
            this.extendBranchFreeFragmentsOnly = extendBranchFreeFragmentsOnly;
            this.keepArtifact = keepArtifact;
            this.keepChimera = keepChimera;
            this.haveFragKmers = haveFragKmers;
            this.minKmerCov = minKmerCov;
            this.writer = writer;
        }
        
        @Override
        public void run() {
            try {
                int fragKmersDist = graph.getFragPairedKmerDistance();
                int maxEdgeClipLength = minPolyATailLengthRequired > 0 ? 0 : maxTipLength;
                boolean keepBluntEndArtifact = keepArtifact;
                
                String fragment;
                while ((fragment = fin.next()) != null) {
                    ++numParsed;
//                    String seq = "";
//                    ArrayList<Kmer> kmers2 = graph.getKmers(seq);
//                    printPairedKmersPositions(kmers2, graph);
                    
                    ArrayList<Kmer> kmers = graph.getKmers(fragment);

                    if (!kmers.isEmpty()) {
                        if ( (!extendBranchFreeFragmentsOnly || isBranchFree(kmers, graph, maxTipLength)) &&
                             !represented(kmers,
                                            graph,
                                            screeningBf,
                                            lookahead,
                                            maxIndelSize,
                                            maxEdgeClipLength,
                                            percentIdentity) &&
                             (keepChimera || !isChimera(kmers, graph, screeningBf, lookahead)) &&
                             (keepBluntEndArtifact || !isBluntEndArtifact(kmers, graph, screeningBf, maxEdgeClipLength)) ) {

                            int[] originalFragRange;

                            if (haveFragKmers) {
                                originalFragRange = extendPE(kmers, graph, maxTipLength, minKmerCov);
                            }
                            else {
                                originalFragRange = extendSE(kmers, graph, maxTipLength, minKmerCov);
                            }

                            int[] currentRange = new int[]{0, kmers.size()};

                            if (haveFragKmers) {
                                if (kmers.size() >= fragKmersDist) {
                                    ArrayDeque<int[]> ranges = breakWithFragPairedKmers(kmers, graph, minNumKmerPairs);
                                    int numFragSegs = ranges.size();

                                    if (numFragSegs == 1) {
                                        currentRange = ranges.peekFirst();
                                    }
                                    else if (numFragSegs > 1) {
                                        int bestOverlap = 0;
                                        int[] bestRange = null;
                                        for (int[] range : ranges) {
                                            int overlap = intervalOverlapSize(range, originalFragRange);
                                            if (overlap > bestOverlap) {
                                                bestOverlap = overlap;
                                                bestRange = range;
                                            }
                                        }

                                        currentRange = bestRange;
                                    }
                                    else {
                                        currentRange = null;
                                    }
                                }
                                else {
                                    currentRange = null;
                                }
                            }

                            if (currentRange != null) {
                                ArrayDeque<int[]> ranges = breakWithReadPairedKmers(kmers, graph, minNumKmerPairs, currentRange[0], currentRange[1]);
                                int numFragSegs = ranges.size();

                                if (numFragSegs > 0) {
                                    if (numFragSegs == 1) {
                                        currentRange = ranges.peekFirst();
                                    }
                                    else if (numFragSegs > 1) {
                                        int bestOverlap = 0;
                                        int[] bestRange = null;
                                        for (int[] range : ranges) {
                                            int overlap = intervalOverlapSize(range, originalFragRange);
                                            if (overlap > bestOverlap) {
                                                bestOverlap = overlap;
                                                bestRange = range;
                                            }
                                        }

                                        currentRange = bestRange;
                                    }

                                    if (currentRange != null) {
                                        ArrayList<Kmer> txptKmers = new ArrayList<>(kmers.subList(currentRange[0], currentRange[1]));

                                        String fragInfo = debug ? fragment : "";

                                        if (!keepArtifact) {
                                            txptKmers = trimReverseComplementArtifact(txptKmers, maxTipLength, maxIndelSize, percentIdentity, graph);
                                        }

                                        writer.write(fragInfo, txptKmers);
//                                            if (!keepArtifact) {
//                                                if (!isTemplateSwitch2(txptKmers, graph, screeningBf, lookahead, percentIdentity)) {
//                                                    txptKmers = trimHairpinBySequenceMatching(txptKmers, k, percentIdentity, graph);
//                                                    transcripts.put(new Transcript(fragInfo, txptKmers));
//                                                }
//                                            }
//                                            else {
//                                            transcripts.put(new Transcript(fragInfo, txptKmers));
//                                            }     
                                    }
                                }
                            }
                        }
                    }
                }
            }
            catch (Exception ex) {
                ex.printStackTrace();
                throw new RuntimeException(ex);
            }
        }
    }
    
    /*
    private class TranscriptWriterWorker implements Runnable {
        
        private final ArrayBlockingQueue<Transcript> transcripts;
        private final TranscriptWriter writer;
        private boolean keepGoing = true;
        
        public TranscriptWriterWorker(ArrayBlockingQueue<Transcript> transcripts,
                                        TranscriptWriter writer) {
            this.transcripts = transcripts;
            this.writer = writer;
        }

        public void stopWhenEmpty () {
            keepGoing = false;
        }
        
        @Override
        public void run() {
            try {
                while (true) {
                    Transcript t = transcripts.poll(10, TimeUnit.MICROSECONDS);
                                        
                    if (t == null) {
                        if (!keepGoing) {
                            break;
                        }
                    }
                    else {
                        writer.write(t.fragment, t.transcriptKmers);
                    }
                }
            }
            catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }
    */
    
    /*
    private class ReadConnector implements Runnable {
        private String left;
        private String right;
        private ArrayBlockingQueue<Fragment> outList;
        private int bound;
        private int minOverlap;
        private boolean storeKmerPairs;
        private boolean extendFragments;
        private int errorCorrectionIterations = 0;
        private float minKmerCov;
        
        public ReadConnector(String left,
                                String right,
                                ArrayBlockingQueue<Fragment> outList,
                                int bound, 
                                int minOverlap,
                                int errorCorrectionIterations,
                                boolean storeKmerPairs,
                                boolean extendFragments,
                                float minKmerCov) {
            
            this.left = left;
            this.right = right;
            this.outList = outList;
            this.bound = bound;
            this.minOverlap = minOverlap;
            this.storeKmerPairs = storeKmerPairs;
            this.extendFragments = extendFragments;
            this.errorCorrectionIterations = errorCorrectionIterations;
            this.minKmerCov = minKmerCov;
        }
        
        @Override
        public void run() {
            try {
                
//                System.out.println("L: " + left);
//                System.out.println("R: " + right);
                
                ArrayList<Kmer> leftKmers = graph.getKmers(left);
                ArrayList<Kmer> rightKmers = graph.getKmers(right);

                if (this.errorCorrectionIterations > 0) {

                    ReadPair correctedReadPair = correctErrorsPE(leftKmers,
                                                        rightKmers,
                                                        graph, 
                                                        lookahead, 
                                                        maxIndelSize, 
                                                        maxCovGradient, 
                                                        covFPR,
                                                        this.errorCorrectionIterations,
                                                        2,
                                                        percentIdentity,
                                                        minKmerCov);

                    if (correctedReadPair.corrected) {
                        leftKmers = correctedReadPair.leftKmers;
                        rightKmers = correctedReadPair.rightKmers;
                    }
                }
                
                if (!leftKmers.isEmpty() && !rightKmers.isEmpty()) {

                    ArrayList<Kmer> fragmentKmers = overlapAndConnect(leftKmers, rightKmers, graph, bound-k+1-leftKmers.size()-rightKmers.size(),
                            lookahead, minOverlap, maxCovGradient, maxTipLength, maxIndelSize, percentIdentity, minKmerCov);

                    ArrayDeque<int[]> ranges = breakWithReadPairedKmers(fragmentKmers, graph, lookahead);
                    
                    if (ranges.size() != 1) {
                        fragmentKmers = null;
                    }
                    
                    if (fragmentKmers != null) {
                        int fragLength = fragmentKmers.size() + k - 1;

                        if (fragLength >= k + lookahead) {
                            boolean hasComplexKmer = false;

                            float minCov = Float.MAX_VALUE;
                            for (Kmer kmer : fragmentKmers) {
                                if (kmer.count < minCov) {
                                    minCov = kmer.count;
                                }

                                if (!hasComplexKmer) {
                                    if (!graph.isLowComplexity(kmer)) {
                                        hasComplexKmer = true;
                                    }
                                }
                            }

                            if (hasComplexKmer) {
                                if (extendFragments) {
                                    fragmentKmers = naiveExtend(fragmentKmers, graph, maxTipLength, minKmerCov);
                                }

                                if (this.storeKmerPairs) {
                                    graph.addFragmentPairKmers(fragmentKmers);
                                }

                                outList.put(new Fragment(left, right, fragmentKmers, fragLength, minCov, false));
                            }
                        }
                    }
                    else {
                        // this is an unconnected read pair
                        float minCov = Float.MAX_VALUE;

                        boolean hasComplexLeftKmer = false;

                        if (leftKmers.size() >= lookahead) {
                            for (Kmer kmer : leftKmers) {
                                if (kmer.count < minCov) {
                                    minCov = kmer.count;
                                }

                                if (!hasComplexLeftKmer && !graph.isLowComplexity(kmer)) {
                                    hasComplexLeftKmer = true;
                                }
                            }
                        }

                        boolean hasComplexRightKmer = false;

                        if (rightKmers.size() >= lookahead) {
                            for (Kmer kmer : rightKmers) {
                                if (kmer.count < minCov) {
                                    minCov = kmer.count;
                                }

                                if (!hasComplexRightKmer && !graph.isLowComplexity(kmer)) {
                                    hasComplexRightKmer = true;
                                }
                            }
                        }

                        if (hasComplexLeftKmer && hasComplexRightKmer) {
                            outList.put(new Fragment(graph.assemble(leftKmers), graph.assemble(rightKmers), null, 0, minCov, true));
                        }
                    }
                }
            }
            catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }
    */
    
    private class FragmentAssembler implements Runnable {
        FastxPairSequenceIterator rin;
        private ArrayBlockingQueue<Fragment> outList;
        private int bound;
        private int minOverlap;
        private boolean storeKmerPairs;
        private int errorCorrectionIterations;
        private int leftReadLengthThreshold;
        private int rightReadLengthThreshold;
        private boolean extendFragments;
        private int minKmerCov;
        private boolean trimArtifact;
        private long numParsed = 0;
        private boolean done = false;
        
        public FragmentAssembler(FastxPairSequenceIterator rin,
                                ArrayBlockingQueue<Fragment> outList,
                                int bound, 
                                int minOverlap, 
                                boolean storeKmerPairs, 
                                int errorCorrectionIterations,
                                int leftReadLengthThreshold,
                                int rightReadLengthThreshold,
                                boolean extendFragments,
                                int minKmerCov,
                                boolean keepArtifact) {
            
            this.rin = rin;
            this.outList = outList;
            this.bound = bound;
            this.minOverlap = minOverlap;
            this.storeKmerPairs = storeKmerPairs;
            this.errorCorrectionIterations = errorCorrectionIterations;
            this.leftReadLengthThreshold = leftReadLengthThreshold;
            this.rightReadLengthThreshold = rightReadLengthThreshold;
            this.extendFragments = extendFragments;
            this.minKmerCov = minKmerCov;
            this.trimArtifact = !keepArtifact;
        }
        
        @Override
        public void run() {
            try {
                PairedReadSegments p;
                while((p = rin.next()) != null) {
                    ++numParsed;
                    
                    ArrayList<Kmer> leftKmers = null;
                    ArrayList<Kmer> rightKmers = null;

                    // connect segments of each read
                    //String left = getBestSegment(p.left, graph);
                    String left = connect(p.left, graph, lookahead);

                    if (left.length() >= this.leftReadLengthThreshold) {
                        if (!isLowComplexity2(left)) {
                            if (minKmerCov > 1) {
                                leftKmers = graph.getKmers(left, minKmerCov);
                            }
                            else {
                                leftKmers = graph.getKmers(left);
                            }
                        }
                    }

                    //String right = getBestSegment(p.right, graph);
                    String right = connect(p.right, graph, lookahead);

                    if (right.length() >= this.rightReadLengthThreshold) {
                        if (!isLowComplexity2(right)) {
                            if (minKmerCov > 1) {
                                rightKmers = graph.getKmers(right, minKmerCov);
                            }
                            else {
                                rightKmers = graph.getKmers(right);
                            }
                        }
                    }

                    boolean leftBad = leftKmers == null || leftKmers.isEmpty();
                    boolean rightBad = rightKmers == null || rightKmers.isEmpty();

                    if (leftBad && rightBad) {
                        continue;
                    }

                    ArrayList<Kmer> fragmentKmers = null;

                    if (!leftBad && !rightBad) {
                        if (errorCorrectionIterations > 0) {
                            ReadPair correctedReadPair = correctErrorsPE(leftKmers,
                                                                        rightKmers,
                                                                        graph, 
                                                                        lookahead, 
                                                                        maxIndelSize, 
                                                                        maxCovGradient, 
                                                                        covFPR,
                                                                        this.errorCorrectionIterations,
                                                                        2,
                                                                        percentIdentity,
                                                                        minKmerCov);

                            if (correctedReadPair.corrected) {
                                leftKmers = correctedReadPair.leftKmers;
                                rightKmers = correctedReadPair.rightKmers;
                            }
                        }

                        fragmentKmers = overlapAndConnect(leftKmers, rightKmers, graph, bound,
                                lookahead, minOverlap, maxCovGradient, maxTipLength, maxIndelSize, percentIdentity, minKmerCov);
                    }
                    else if (!leftBad) {
                        if (errorCorrectionIterations > 0) {
                            ArrayList<Kmer> corrected = correctErrorsSE(leftKmers,
                                                                        graph, 
                                                                        lookahead, 
                                                                        maxIndelSize, 
                                                                        maxCovGradient, 
                                                                        covFPR,
                                                                        percentIdentity,
                                                                        minKmerCov);
                            if (corrected != null && !corrected.isEmpty()) {
                                leftKmers = corrected;
                            }
                        }
                    }
                    else if (!rightBad) {
                        if (errorCorrectionIterations > 0) {
                            ArrayList<Kmer> corrected = correctErrorsSE(rightKmers,
                                                                        graph, 
                                                                        lookahead, 
                                                                        maxIndelSize, 
                                                                        maxCovGradient, 
                                                                        covFPR,
                                                                        percentIdentity,
                                                                        minKmerCov);
                            if (corrected != null && !corrected.isEmpty()) {
                                rightKmers = corrected;
                            }
                        }
                    }

                    // check for read consistency if fragment is long enough
                    int preExtensionFragLen = -1;

                    if (fragmentKmers != null) {
                        if (graph.getReadPairedKmerDistance() < fragmentKmers.size()) {
                            ArrayDeque<int[]> ranges = breakWithReadPairedKmers(fragmentKmers, graph, lookahead);

                            if (ranges.size() != 1) {
                                fragmentKmers = null;
                            }
                            else {
                                int[] range = ranges.peek();
                                if (range[0] >= leftKmers.size() || range[1] < fragmentKmers.size() - rightKmers.size()) {
                                    // fragment is a spurious connection between left and right reads
                                    fragmentKmers = null;
                                }
                                else if (range[0] > 0 || range[1] < fragmentKmers.size()) {
                                    // trim the fragment
                                    fragmentKmers = new ArrayList<>(fragmentKmers.subList(range[0], range[1]));
                                }
                            }
                        }

                        if (fragmentKmers != null && trimArtifact) {
                            fragmentKmers = trimReverseComplementArtifact(fragmentKmers, maxTipLength, maxIndelSize, percentIdentity, graph);
                            //fragmentKmers = trimHairpinBySequenceMatching(fragmentKmers, k, percentIdentity, graph);
                        }

                        if (fragmentKmers != null) {
                            preExtensionFragLen = fragmentKmers.size() + k - 1;

                            if (extendFragments) {
                                ArrayList<Kmer> extendedFragmentKmers = naiveExtend(fragmentKmers, graph, maxTipLength, minKmerCov);

                                if (extendedFragmentKmers.size() != preExtensionFragLen) {
                                    // fragment was extended; check consistency with reads
                                    ArrayDeque<int[]> ranges = breakWithReadPairedKmers(extendedFragmentKmers, graph, minNumKmerPairs);

                                    if (ranges.size() == 1) {
                                        // there is one consistent section in the extended fragment
                                        int[] range = ranges.peek();
                                        if (range[0] > 0 || range[1] < extendedFragmentKmers.size()) {
                                            // trim extended fragment
                                            fragmentKmers = new ArrayList<>(extendedFragmentKmers.subList(range[0], range[1]));
                                        }
                                        else {
                                            // trimming not needed; replace original fragment with extended fragment
                                            fragmentKmers = extendedFragmentKmers;
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (fragmentKmers != null) {
                        if (fragmentKmers.size() + k - 1 >= k + lookahead) {
                            boolean hasComplexKmer = false;

                            float minCov = Float.MAX_VALUE;
                            for (Kmer kmer : fragmentKmers) {
                                if (kmer.count < minCov) {
                                    minCov = kmer.count;
                                }

                                if (!hasComplexKmer) {
                                    if (!graph.isRepeatKmer(kmer)) {
                                        hasComplexKmer = true;
                                    }
                                }
                            }

                            if (hasComplexKmer) {
                                if (this.storeKmerPairs) {
                                    graph.addFragmentPairKmers(fragmentKmers);
                                }

                                if (!debug) {
                                   left = "";
                                   right = "";
                                }

                                outList.put(new Fragment(left, right, fragmentKmers, preExtensionFragLen, minCov, false));
                            }
                        }
                    }
                    else {
                        // this is an unconnected read pair
                        float minCov = Float.MAX_VALUE;

                        boolean hasComplexLeftKmer = false;

                        if (!leftBad && leftKmers.size() >= lookahead) {
                            for (Kmer kmer : leftKmers) {
                                if (kmer.count < minCov) {
                                    minCov = kmer.count;
                                }

                                if (!hasComplexLeftKmer && !graph.isRepeatKmer(kmer)) {
                                    hasComplexLeftKmer = true;
                                }
                            }
                        }

                        boolean hasComplexRightKmer = false;

                        if (!rightBad && rightKmers.size() >= lookahead) {
                            for (Kmer kmer : rightKmers) {
                                if (kmer.count < minCov) {
                                    minCov = kmer.count;
                                }

                                if (!hasComplexRightKmer && !graph.isRepeatKmer(kmer)) {
                                    hasComplexRightKmer = true;
                                }
                            }
                        }

                        if (hasComplexLeftKmer || hasComplexRightKmer) {
                            left = leftBad ? "" : left;
                            right = rightBad ? "" : right;

                            outList.put(new Fragment(left, right, null, 0, minCov, true));
                        }
                    }
                }
            }
            catch (Exception ex) {
                ex.printStackTrace();
                throw new RuntimeException(ex);
            }
            done = true;
        }
        
        public void updateBound(int bound) {
            this.bound = bound;
        }
    }
    
    public class MyExecutorService {
        private final BlockingQueue<Runnable> queue;
        private final ExecutorService service;
        
        public MyExecutorService(int numThreads, int queueSize) {
            queue = new ArrayBlockingQueue<>(queueSize);    
            service = new ThreadPoolExecutor(numThreads, numThreads,
                        0L, TimeUnit.MILLISECONDS,
                        queue);
        }
        
        public void submit(Runnable r) {
            while (true) {
                if (queue.remainingCapacity() > 0) {
                    service.submit(r);
                    break;                    
                }
            }
        }
        
        public void terminate() throws InterruptedException {
            service.shutdown();
            service.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
        
        public int getQueueRemainingCapacity() {
            return queue.remainingCapacity();
        }
    }
        
    private int[] getMinQ1MedianQ3Max(ArrayList<Integer> a) {
        if (a.isEmpty()) {
            int[] result = new int[5];
            Arrays.fill(result, 0);
            return result;
        }
        
        if (a.size() == 1) {
            int[] result = new int[5];
            Arrays.fill(result, a.get(0));
            return result;
        }
        
        Collections.sort(a);
        
        int arrLen = a.size();
        int halfLen = arrLen/2;
        int q1Index = arrLen/4;
        int q3Index = halfLen+q1Index;
        
        int q1, median, q3;
        
        if (arrLen % 2 == 0) {
            median = (a.get(halfLen-1) + a.get(halfLen))/2;
        }
        else {
            median = a.get(halfLen);
        }
        
        if (arrLen % 4 == 0) {
            q1 = (a.get(q1Index-1) + a.get(q1Index))/2;
            q3 = (a.get(q3Index-1) + a.get(q3Index))/2;
        }
        else {
            q1 = a.get(q1Index);
            q3 = a.get(q3Index);
        }
        
        return new int[]{a.get(0), q1, median, q3, a.get(arrLen-1)};
    }
    
    private static int getCoverageOrderOfMagnitude(float c) {
        if (c >= 1e5) {
            return 5;
        }
        else if (c >= 1e4) {
            return 4;
        }
        else if (c >= 1e3) {
            return 3;
        }
        else if (c >= 1e2) {
            return 2;
        }
        else if (c >= 1e1) {
            return 1;
        }
        else {
            return 0;
        }
    }
    
    public void setupKmerScreeningBloomFilter(long sbfNumBits, int sbfNumHash) {
        if (screeningBf == null) {
            screeningBf = new BloomFilter(sbfNumBits, sbfNumHash, graph.getHashFunction());
        }
        else {
            screeningBf.empty();
        }
    }
    
    public void setupFragmentPairedKmersBloomFilter(long numBits, int numHash) {
        graph.initializePairKmersBloomFilter(numBits, numHash);
    }
    
    public void updateFragmentKmerDistance(String graphFile) throws IOException {
        graph.updateFragmentKmerDistance(new File(graphFile));
    }
    
    /*
    public void rescueUnconnectedMultiThreaded(String[] fastas, 
                                                String[] longFragmentsFastaPaths,
                                                String[] shortFragmentsFastaPaths,
                                                String[] unconnectedReadsFastaPaths,
                                                String longSingletonsFasta,
                                                String shortSingletonsFasta,
                                                String unconnectedSingletonsFasta,
                                                int bound,
                                                int minOverlap,
                                                int sampleSize, 
                                                int numThreads, 
                                                int maxErrCorrIterations,
                                                boolean extendFragments,
                                                float minKmerCov) throws IOException, InterruptedException {
        
        // make sure paired kmer distance is set
        
        if (dbgFPR <= 0) {
            dbgFPR = graph.getDbgbf().getFPR();
        }
        
        if (covFPR <= 0) {
            covFPR = graph.getCbf().getFPR();
        }
        
        System.out.println("DBG Bloom filter FPR:      " + dbgFPR * 100 + " %");
        System.out.println("Counting Bloom filter FPR: " + covFPR * 100 + " %");
        
        
        System.out.println("Rescuing unconnected read pairs...");
                
        long fragmentId = 0;
        long unconnectedReadId = 0;
        long readPairsParsed = 0;
        long rescuedReadPairs = 0;
        
        int maxTasksQueueSize = numThreads;
        
        int newBound = bound;
        int shortestFragmentLengthAllowed = k + lookahead;
        
        // set up thread pool
        MyExecutorService service = new MyExecutorService(numThreads, maxTasksQueueSize);

        int maxConcurrentSubmissions = numThreads + maxTasksQueueSize;
        
        FastaReader in;

        FastaWriter[] longFragmentsOut = new FastaWriter[]{new FastaWriter(longFragmentsFastaPaths[0], true),
                                                            new FastaWriter(longFragmentsFastaPaths[1], true),
                                                            new FastaWriter(longFragmentsFastaPaths[2], true),
                                                            new FastaWriter(longFragmentsFastaPaths[3], true),
                                                            new FastaWriter(longFragmentsFastaPaths[4], true),
                                                            new FastaWriter(longFragmentsFastaPaths[5], true)};

        FastaWriter[] shortFragmentsOut = new FastaWriter[]{new FastaWriter(shortFragmentsFastaPaths[0], true),
                                                            new FastaWriter(shortFragmentsFastaPaths[1], true),
                                                            new FastaWriter(shortFragmentsFastaPaths[2], true),
                                                            new FastaWriter(shortFragmentsFastaPaths[3], true),
                                                            new FastaWriter(shortFragmentsFastaPaths[4], true),
                                                            new FastaWriter(shortFragmentsFastaPaths[5], true)};

        FastaWriter[] unconnectedReadsOut = new FastaWriter[]{new FastaWriter(unconnectedReadsFastaPaths[0], true),
                                                            new FastaWriter(unconnectedReadsFastaPaths[1], true),
                                                            new FastaWriter(unconnectedReadsFastaPaths[2], true),
                                                            new FastaWriter(unconnectedReadsFastaPaths[3], true),
                                                            new FastaWriter(unconnectedReadsFastaPaths[4], true),
                                                            new FastaWriter(unconnectedReadsFastaPaths[5], true)};

        FastaWriter longSingletonsOut = new FastaWriter(longSingletonsFasta, true);
        FastaWriter shortSingletonsOut = new FastaWriter(shortSingletonsFasta, true);
        FastaWriter unconnectedSingletonsOut = new FastaWriter(unconnectedSingletonsFasta, true);

        ArrayBlockingQueue<Fragment> fragments = new ArrayBlockingQueue<>(sampleSize);

        for (String fasta : fastas) {
            in = new FastaReader(fasta);

            System.out.println("Parsing `" + fasta + "`...");

            // assemble the remaining fragments in multi-threaded mode
            while (in.hasNext()) {
                String left = in.next();
                String right = in.next();
                ++readPairsParsed;

                if (left.length() >= k && right.length() >= k) {
                    service.submit(new ReadConnector(left,
                                                    right,
                                                    fragments,
                                                    newBound, 
                                                    minOverlap,
                                                    maxErrCorrIterations,
                                                    false, // don't store the kmer pairs
                                                    extendFragments,
                                                    minKmerCov
                    ));
                }

                if (fragments.remainingCapacity() <= maxConcurrentSubmissions) {

                    // write fragments to file
                    int m;
                    Fragment frag;
                    for (int i=0; i<sampleSize; ++i) {
                        frag = fragments.poll();

                        if (frag == null) {
                            break;
                        }

                        if (frag.minCov >= 2) {
                            //When reads were parsed at k2, kmers common to both fragments and reads have counts incremented by 1.
                            //Kmers unique to fragments would have a count of 1.
                            //So, min kmer counts >= 2 need to be decremented by 1 when assigning fragments.
                            --frag.minCov;
                        }

                        if (frag.isUnconnectedRead) {
                            if (frag.minCov == 1) {
                                unconnectedSingletonsOut.write("r" + Long.toString(++unconnectedReadId) + "L ", frag.left);
                                unconnectedSingletonsOut.write("r" + Long.toString(unconnectedReadId) + "R", frag.right);
                            }
                            else if (frag.minCov > 1) {
                                m = getCoverageOrderOfMagnitude(frag.minCov);

                                if (m >= 0) {
                                    unconnectedReadsOut[m].write("r" + Long.toString(++unconnectedReadId) + "L ", frag.left);
                                    unconnectedReadsOut[m].write("r" + Long.toString(unconnectedReadId) + "R", frag.right);
                                }
                            }
                        }
                        else {
                            ++rescuedReadPairs;

                            if (frag.length >= shortestFragmentLengthAllowed) {
                                ArrayList<Kmer> fragKmers = frag.kmers;

                                if (!containsAllKmers(screeningBf, fragKmers) || !graph.containsAllPairedKmers(fragKmers)) {
                                    if (frag.minCov == 1) {
                                        graph.addFragmentPairKmers(fragKmers);

                                        if (frag.length >= longFragmentLengthThreshold) {
                                            for (Kmer kmer : fragKmers) {
                                                screeningBf.add(kmer.getHash());
                                            } 

                                            longSingletonsOut.write("r" + Long.toString(++fragmentId) + " L=[" + frag.left + "] R=[" + frag.right + "]", graph.assemble(frag.kmers));
                                        }
                                        else {
                                            shortSingletonsOut.write("r" + Long.toString(++fragmentId) + " L=[" + frag.left + "] R=[" + frag.right + "]", graph.assemble(frag.kmers));
                                        }
                                    }
                                    else if (frag.minCov > 1) {
                                        m = getCoverageOrderOfMagnitude(frag.minCov);

                                        if (m >= 0) {
                                            graph.addFragmentPairKmers(fragKmers);

                                            if (frag.length >= longFragmentLengthThreshold) {
                                                for (Kmer kmer : fragKmers) {
                                                    screeningBf.add(kmer.getHash());
                                                }

                                                longFragmentsOut[m].write("r" + Long.toString(++fragmentId) + " L=[" + frag.left + "] R=[" + frag.right + "]", graph.assemble(frag.kmers));
                                            }
                                            else {
                                                shortFragmentsOut[m].write("r" + Long.toString(++fragmentId) + " L=[" + frag.left + "] R=[" + frag.right + "]", graph.assemble(frag.kmers));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            in.close();
        }

        service.terminate();

        // write fragments to file
        int m;
        Fragment frag;
        while (!fragments.isEmpty()) {
            frag = fragments.poll();

            if (frag.isUnconnectedRead) {
                if (frag.minCov == 1) {
                    unconnectedSingletonsOut.write("r" + Long.toString(++unconnectedReadId) + "L ", frag.left);
                    unconnectedSingletonsOut.write("r" + Long.toString(unconnectedReadId) + "R", frag.right);
                }
                else if (frag.minCov > 1) {
                    m = getCoverageOrderOfMagnitude(frag.minCov);

                    if (m >= 0) {
                        unconnectedReadsOut[m].write("r" + Long.toString(++unconnectedReadId) + "L ", frag.left);
                        unconnectedReadsOut[m].write("r" + Long.toString(unconnectedReadId) + "R", frag.right);
                    }
                }
            }
            else {
                ++rescuedReadPairs;

                if (frag.length >= shortestFragmentLengthAllowed) {
                    ArrayList<Kmer> fragKmers = frag.kmers;

                    if (!containsAllKmers(screeningBf, fragKmers) || !graph.containsAllPairedKmers(fragKmers)) {
                        if (frag.minCov == 1) {
                            graph.addFragmentPairKmers(fragKmers);

                            if (frag.length >= longFragmentLengthThreshold) {
                                for (Kmer kmer : fragKmers) {
                                    screeningBf.add(kmer.getHash());
                                }

                                longSingletonsOut.write("r" + Long.toString(++fragmentId) + " L=[" + frag.left + "] R=[" + frag.right + "]", graph.assemble(frag.kmers));
                            }
                            else {
                                shortSingletonsOut.write("r" + Long.toString(++fragmentId) + " L=[" + frag.left + "] R=[" + frag.right + "]", graph.assemble(frag.kmers));
                            }
                        }
                        else if (frag.minCov > 1)  {
                            m = getCoverageOrderOfMagnitude(frag.minCov);

                            if (m >= 0) {
                                graph.addFragmentPairKmers(fragKmers);

                                if (frag.length >= longFragmentLengthThreshold) {
                                    for (Kmer kmer : fragKmers) {
                                        screeningBf.add(kmer.getHash());
                                    }

                                    longFragmentsOut[m].write("r" + Long.toString(++fragmentId) + " L=[" + frag.left + "] R=[" + frag.right + "]", graph.assemble(frag.kmers));
                                }
                                else {
                                    shortFragmentsOut[m].write("r" + Long.toString(++fragmentId) + " L=[" + frag.left + "] R=[" + frag.right + "]", graph.assemble(frag.kmers));
                                }
                            }
                        }
                    }
                }
            }
        }

        unconnectedSingletonsOut.close();
        longSingletonsOut.close();
        shortSingletonsOut.close();

        for (FastaWriter out : longFragmentsOut) {
            out.close();
        }

        for (FastaWriter out : shortFragmentsOut) {
            out.close();
        }

        for (FastaWriter out : unconnectedReadsOut) {
            out.close();
        }

        System.out.println("Parsed " + NumberFormat.getInstance().format(readPairsParsed) + " read pairs.");
        System.out.println("Rescued " + NumberFormat.getInstance().format(rescuedReadPairs) + " read pairs.");
        System.out.println("Paired kmers Bloom filter FPR: " + graph.getPkbfFPR() * 100   + " %");
        System.out.println("Screening Bloom filter FPR:    " + screeningBf.getFPR() * 100 + " %");    
    }
    */
    
    private final static String LABEL_SEPARATOR = ":";
    private final static String LABEL_MIN = "min";
    private final static String LABEL_Q1 = "Q1";
    private final static String LABEL_MEDIAN = "M";
    private final static String LABEL_Q3 = "Q3";
    private final static String LABEL_MAX = "max";
    
    public void writeFragStatsToFile(int[] fragStats, String path) throws IOException {
        FileWriter writer = new FileWriter(path, false);

        writer.write(LABEL_MIN + LABEL_SEPARATOR + fragStats[0] + "\n" +
                    LABEL_Q1 + LABEL_SEPARATOR + fragStats[1] + "\n" +
                    LABEL_MEDIAN + LABEL_SEPARATOR + fragStats[2] + "\n" +
                    LABEL_Q3 + LABEL_SEPARATOR + fragStats[3] + "\n" +
                    LABEL_MAX + LABEL_SEPARATOR + fragStats[4] + "\n"
                );
        writer.close();
    }
    
    public int[] restoreFragStatsFromFile(String path) throws IOException {
        int[] fragStats = new int[5];
        
        BufferedReader br = new BufferedReader(new FileReader(path));
        String line;
        while ((line = br.readLine()) != null) {
            String[] entry = line.split(LABEL_SEPARATOR);
            String key = entry[0];
            String val = entry[1];
            switch(key) {
                case LABEL_MIN:
                    fragStats[0] = Integer.parseInt(val);
                    break;
                case LABEL_Q1:
                    fragStats[1] = Integer.parseInt(val);
                    break;
                case LABEL_MEDIAN:
                    fragStats[2] = Integer.parseInt(val);
                    break;
                case LABEL_Q3:
                    fragStats[3] = Integer.parseInt(val);
                    break;
                case LABEL_MAX:
                    fragStats[4] = Integer.parseInt(val);
                    break;
            }
        }
        br.close();

        longFragmentLengthThreshold = fragStats[1];
          
        return fragStats;
    }
    
    private void setPairedKmerDistance(int fragStatQ1) {
        graph.setFragPairedKmerDistance(fragStatQ1 - k - minNumKmerPairs);
    }
    
    private int getPairedReadsMaxDistance(int[] fragStats) {
        return fragStats[3] + ((fragStats[3] - fragStats[1]) * 3 / 2); // 1.5*IQR
    }
    
    public class ContainmentCalculator implements Runnable {
        private final long[] queryHashVals;
        private int startIndex = -1;
        private int stopIndex = -1;
        private int bestOverlap = -1;
//        private float bestOverlapProportion = -1;
        private int bestTargetIndex = -1;
        private final ArrayList<long[]> targetSketches;
        private boolean successful = false;
        private Exception exception = null;
        private int minSketchOverlap = -1;
//        private float minSketchOverlapProportion = -1;
        private ArrayDeque<Integer> overlappingSketchIndexes = new ArrayDeque<>();
        
        public ContainmentCalculator(long[] queryHashVals, ArrayList<long[]> targetSketches, 
                                    int startIndex, int stopIndex,
                                    int minSketchOverlap) {
            this.queryHashVals = queryHashVals;
            this.startIndex = startIndex;
            this.stopIndex= stopIndex;
            this.targetSketches = targetSketches;
            this.minSketchOverlap = minSketchOverlap;
//            this.minSketchOverlapProportion = minSketchOverlapProportion;
        }
        
        @Override
        public void run() {
            try {
                for(int sketchID=startIndex; sketchID<stopIndex; ++sketchID) {
                    long[] sketch = targetSketches.get(sketchID);
                    
                    if (sketch == null) {
                        continue;
                    }
                 
//                    int overlap = getNumIntersection(queryHashVals, sketch);
//                    float overlapProportion = Math.max((float)overlap / (float)sketch.length, (float)overlap / (float)queryHashVals.length);
//                    if (overlapProportion >= minSketchOverlapProportion && overlap >= minSketchOverlap) {
                    if (hasNumIntersection(queryHashVals, sketch, minSketchOverlap)) {
                        if (bestTargetIndex < 0) {
                            bestTargetIndex = sketchID;
                            bestOverlap = minSketchOverlap;
//                            bestOverlapProportion = overlapProportion;
                        }
                        else {
                            overlappingSketchIndexes.add(sketchID);
//                            bestTargetIndex = sketchID;
//                            bestOverlap = minIntersection;
//                            bestOverlapProportion = overlapProportion;
                        }
                    }
                }
                
                successful = true;
                
            } catch (Exception ex) {
                exception = ex;
                successful = false;
            }
        }
        
        public int getBestTargetIndex() {
            return bestTargetIndex;
        }
        
        public int getMaxIntersection() {
            return bestOverlap;
        }
        
        public ArrayDeque<Integer> getOverlappingSketchIndexes() {
            return overlappingSketchIndexes;
        }
        
        public boolean isSucessful() {
            return successful;
        }
        
        public Exception getExceptionCaught() {
            return exception;
        }
    }
    
    private static void mergeClusterFastas(int target, int maxAltID, String clusteredLongReadsDirectory) throws IOException {
        String path = clusteredLongReadsDirectory + File.separator + target + FASTA_EXT;
        
        FastaWriter writer = new FastaWriter(path, true);
        for (int i=0; i<=maxAltID; ++i) {
            path = clusteredLongReadsDirectory + File.separator + target + "_" + i + FASTA_EXT;
            FastaReader reader = new FastaReader(path);
            while (reader.hasNext()) {
                String[] nameSeqPair = reader.nextWithName();
                writer.write(nameSeqPair[0], nameSeqPair[1]);
            }
            reader.close();
            
            Files.deleteIfExists(FileSystems.getDefault().getPath(path));
        }
        writer.close();
    }
    
    private static int aggregateClusterFastas(int target, HashSet<Integer> overlaps, ArrayList<Integer> altIDs, String clusteredLongReadsDirectory) throws IOException {
        int altID = altIDs.get(target);
        
        for (int i : overlaps) {
            Path source = Paths.get(clusteredLongReadsDirectory + File.separator + i + FASTA_EXT);
            Path dest = Paths.get(clusteredLongReadsDirectory + File.separator + target + "_" + ++altID + FASTA_EXT);
            Files.move(source, dest);
            
            int overlapAltID = altIDs.get(i);
            for (int j=0; j<=overlapAltID; ++j) {
                source = Paths.get(clusteredLongReadsDirectory + File.separator + i + "_" + j + FASTA_EXT);
                dest = Paths.get(clusteredLongReadsDirectory + File.separator + target + "_" + ++altID + FASTA_EXT);
                Files.move(source, dest);
            }
            
        }
        
        return altID;
    }
    
    public void clusterLongReads(String[][] correctedLongReadFileNames, String clusteredLongReadsDirectory,
            int minSketchSize, int numThreads, boolean useCompressedMinimizers,
            int minimizerSize, int minimizerWindowSize, float minSketchOverlapPercentage, int minSketchOverlapNumber) throws IOException, InterruptedException {
        
        minSketchSize = Math.max(minSketchSize, minSketchOverlapNumber);
        
        ArrayList<long[]> targetSketches = new ArrayList<>();
        ArrayList<ArrayDeque<BitSequence>> targetSequences = new ArrayList<>();
        ArrayDeque<Integer> targetSketchesNullIndexes = new ArrayDeque<>();
        NTHashIterator itr = graph.getHashIterator(1, minimizerSize);
        
        int maxClusterSize = 1; // each cluster has a minimum of 1 read
        int numDiscarded = 0;
        
        //for (int l=LENGTH_STRATUM_NAMES.length-1; l>0; --l) {
        for (int c=COVERAGE_ORDER.length-1; c>=0; --c) {

            //for (int c=COVERAGE_ORDER.length-1; c>=0; --c) {
            for (int l=LENGTH_STRATUM_NAMES.length-1; l>=0; --l) {
                
                String readFile = correctedLongReadFileNames[c][l];
                FastaReader fr = new FastaReader(readFile);
                System.out.println("Parsing file `" + readFile + "`...");
                
                while (fr.hasNext()) {
                    String seq = fr.next();
                    
                    int numMinimizers = getNumKmers(seq, minimizerSize);
                    long[] sortedHashVals = useCompressedMinimizers ? 
                                            getAscendingHashValuesWithCompressedHomoPolymers(seq, itr, minimizerSize) : 
                                            getAscendingHashValues(seq, itr, numMinimizers);
                    
                    int numHashVals = sortedHashVals.length;
                    
                    if (numHashVals < minSketchSize) {
                        // not enough good kmers
                        ++numDiscarded;
                        continue;
                    }                   

                    int bestTargetSketchID = -1;
                    int numTargets = targetSketches.size();
                    
                    if (numTargets == 0) {
                        long[] sketch = useCompressedMinimizers ?
                                        getMinimizersWithCompressedHomoPolymers(seq, minimizerSize, itr, minimizerWindowSize) :
                                        getMinimizers(seq, numMinimizers, itr, minimizerWindowSize);
                        targetSketches.add(sketch);
                        
                        bestTargetSketchID = 0;
                        
                        ArrayDeque<BitSequence> seqs = new ArrayDeque<>();
                        seqs.add(new BitSequence(seq));
                        targetSequences.add(seqs);
                    }
                    else {
                        int numNonOverlapMinimizers = (numMinimizers-minimizerWindowSize+1)/minimizerWindowSize;
                        int minSketchOverlap = Math.max(minSketchOverlapNumber, (int) Math.ceil(minSketchOverlapPercentage * numNonOverlapMinimizers));
                        
                        /** start thread pool*/
                        int numWorkers = Math.min(numThreads, numTargets);
                        MyExecutorService service = new MyExecutorService(numWorkers, numWorkers);
                        
                        ContainmentCalculator[] workers = new ContainmentCalculator[numWorkers];
                        int step = numTargets/numWorkers;
                        int startIndex = 0;
                        int stopIndex = step;
                        for (int i=0; i<numWorkers-1; ++i) {
                            ContainmentCalculator worker = new ContainmentCalculator(sortedHashVals, targetSketches, startIndex, stopIndex, minSketchOverlap);
                            workers[i] = worker;
                            service.submit(worker);
                            startIndex = stopIndex;
                            stopIndex += step;
                        }
                        
                        ContainmentCalculator w = new ContainmentCalculator(sortedHashVals, targetSketches, startIndex, targetSketches.size(), minSketchOverlap);
                        workers[numWorkers-1] = w;
                        service.submit(w);
                                                
                        service.terminate();
                        
                        int bestIntersectionSize = -1;
                        ArrayDeque<Integer> overlapSketchIDs = new ArrayDeque<>();
                        for (ContainmentCalculator worker : workers) {
                            int mc = worker.getMaxIntersection();
                            if (mc > 0) {
                                ArrayDeque<Integer> overlaps = worker.getOverlappingSketchIndexes();
                                if (!overlaps.isEmpty()) {
                                    overlapSketchIDs.addAll(overlaps);
                                }
                                
                                if (mc > bestIntersectionSize) {
                                    if (bestTargetSketchID >= 0) {
                                        overlapSketchIDs.add(bestTargetSketchID);
                                    }
                                    bestIntersectionSize = mc;
                                    bestTargetSketchID = worker.getBestTargetIndex();
                                }
                            }
                        }
                        
                        if (bestIntersectionSize < minSketchOverlap) {
                            long[] sketch = useCompressedMinimizers ?
                                    getMinimizersWithCompressedHomoPolymers(seq, minimizerSize, itr, minimizerWindowSize) :
                                    getMinimizers(seq, numMinimizers, itr, minimizerWindowSize);
                            // start a new cluster                            
                            if (targetSketchesNullIndexes.isEmpty()) {
                                targetSketches.add(sketch);
                                
                                ArrayDeque<BitSequence> seqs = new ArrayDeque<>();
                                seqs.add(new BitSequence(seq));
                                targetSequences.add(seqs);
                            }
                            else {
                                bestTargetSketchID = targetSketchesNullIndexes.poll();
                                targetSketches.set(bestTargetSketchID, sketch);
                                
                                ArrayDeque<BitSequence> seqs = new ArrayDeque<>();
                                seqs.add(new BitSequence(seq));
                                targetSequences.set(bestTargetSketchID, seqs);
                            }
                        }
                        else {
                            ArrayDeque<BitSequence> seqs = targetSequences.get(bestTargetSketchID);
                            seqs.add(new BitSequence(seq));
                            
                            if (!overlapSketchIDs.isEmpty()) {
                                // combine overlapping clusters
                                
                                ArrayDeque<long[]> overlappingSketches = new ArrayDeque<>();
                                overlappingSketches.add(targetSketches.get(bestTargetSketchID));
                                
                                // add the sketches of other targets
                                for (int i : overlapSketchIDs) {
                                    overlappingSketches.add(targetSketches.get(i));
                                    targetSketches.set(i, null);
                                    targetSketchesNullIndexes.add(i);
                                    
                                    seqs.addAll(targetSequences.get(i));
                                    targetSequences.set(i, null);
                                }
                                
                                long[] sketch = useCompressedMinimizers ?
                                        getMinimizersWithCompressedHomoPolymers(seq, minimizerSize, itr, minimizerWindowSize) :
                                        getMinimizers(seq, numMinimizers, itr, minimizerWindowSize);
                                overlappingSketches.add(sketch);
                                
                                targetSketches.set(bestTargetSketchID, combineSketches(overlappingSketches));
                            }
//                            else if (numNonOverlapMinimizers-bestIntersectionSize > minSketchOverlapNumber) {
//                                long[] sketch = useCompressedMinimizers ?
//                                                getMinimizersWithCompressedHomoPolymers(seq, minimizerSize, itr, minimizerWindowSize) :
//                                                getMinimizers(seq, numKmers, itr, minimizerWindowSize);
//                                targetSketches.set(bestTargetSketchID, combineSketches(sketch, targetSketches.get(bestTargetSketchID)));
//                            }
                            
                            maxClusterSize = Math.max(maxClusterSize, seqs.size());
                        }
                    }
                }
                
                fr.close();
                
                long numClusters = targetSketches.size() - targetSketchesNullIndexes.size();
                if (numClusters > 0) {
                    System.out.println("Num. clusters: " + numClusters + "\tmax. size: " + maxClusterSize);
                }
            }
            
            /*
            if (numSeq > 0) {
                int numSketches = targetSketches.size();
                double averageOverlapProportions = sumOverlapProportions/numSeq;
                System.out.println("avg. overlap: " + averageOverlapProportions);
                System.out.println("sketches: " + numSketches);

                int numOverlaps = 0;
                for (int i=0; i<numSketches; ++i) {
                    TreeSet<Long> qSketch = targetSketches.get(i);
                    if (qSketch != null) {
                        double qSketchSize = qSketch.size();
                        ArrayDeque<Integer> overlappingSketchIDs = new ArrayDeque<>();

                        for (int j=i+1; j<numSketches; ++j) {
                            TreeSet<Long> tSketch = targetSketches.get(j);
                            if (tSketch != null) {
                                int overlap = getNumIntersection(qSketch, tSketch);
                                if ((double)overlap/qSketchSize >= averageOverlapProportions ||
                                        (double)overlap/(double)tSketch.size() >= averageOverlapProportions) {
                                    overlappingSketchIDs.add(j);
                                }
                            }
                        }

                        if (!overlappingSketchIDs.isEmpty()) {
                            numOverlaps += overlappingSketchIDs.size();

                            ArrayDeque<String> seqIDs = targetSketchesSeqIDs.get(i);

                            // merge sketches
                            for (int sid : overlappingSketchIDs) {
                                qSketch.addAll(targetSketches.get(sid));
                                targetSketches.set(sid, null);
                                targetSketchesNullIndexes.add(sid);

                                seqIDs.addAll(targetSketchesSeqIDs.get(sid));
                                targetSketchesSeqIDs.set(sid, null);
                            }
                        }
                    }
                }
                System.out.println("sketch overlaps: " +  numOverlaps);
            }
            */
        }
        
        System.out.println(NumberFormat.getInstance().format(numDiscarded) + " reads were discarded.");
        
        System.out.println("Writing clustered reads to files...");
        int clusterID = 0;
        long seqID = 0;
        ArrayList<Integer> clusterSizes = new ArrayList<>(targetSequences.size());
        for (ArrayDeque<BitSequence> seqs : targetSequences) {
            if (seqs != null) {
                FastaWriter writer = new FastaWriter(clusteredLongReadsDirectory + File.separator + clusterID + FASTA_EXT, true);
                
                for (BitSequence b : seqs) {
                    writer.write("r" + Long.toString(seqID++), b.toString());
                }
                
                clusterSizes.add(seqs.size());
                
                if (seqs.size() == maxClusterSize) {
                    System.out.println("Largest cluster (" + maxClusterSize + ") at \"" + clusterID + "\"");
                }
                
                writer.close();
                
                ++clusterID;
            }
        }
        
        System.out.println("Cluster Sizes Distribution");
        int[] csd = getMinQ1MedianQ3Max(clusterSizes);
        System.out.println("\tmin\tQ1\tM\tQ3\tmax");
        System.out.println("\t" + csd[0] + "\t" + csd[1] + "\t" + csd[2] + "\t" + csd[3] + "\t" + csd[4]);
        System.out.println(NumberFormat.getInstance().format(seqID) + " reads were assigned to " + NumberFormat.getInstance().format(clusterID) + " clusters.");
    }
    
    /*
    public boolean assembleLongReads(String clusteredLongReadsDirectory, 
                                    String assembledLongReadsDirectory, 
                                    String assembledLongReadsCombined,
                                    int numThreads,
                                    boolean writeUracil,
                                    String minimapOptions,
                                    int minKmerCov,
                                    String txptNamePrefix,
                                    boolean stranded,
                                    int minTranscriptLength,
                                    boolean removeArtifacts,
                                    boolean usePacBioPreset) throws IOException {
        
        ArrayList<Integer> clusterIDs = new ArrayList<>();
        
        for (File f : new File(clusteredLongReadsDirectory).listFiles()) {
            String name = f.getName();
            if (name.endsWith(FASTA_EXT)) {
                clusterIDs.add(Integer.parseInt(name.substring(0, name.lastIndexOf(FASTA_EXT))));
            }
        }
        
        Collections.sort(clusterIDs);
        System.out.println("Total of " + clusterIDs.size() + " clusters to be assembled");
        
        ArrayList<Integer> errors = new ArrayList<>();
        for (int clusterID : clusterIDs) {
            String stampPath = assembledLongReadsDirectory + File.separator + clusterID + ".DONE";
            File stampFile = new File(stampPath);
            
            if (!stampFile.exists()) {
                String readsPath = clusteredLongReadsDirectory + File.separator + clusterID + FASTA_EXT;
                String tmpPrefix = assembledLongReadsDirectory + File.separator + clusterID;
                String concensusPath = assembledLongReadsDirectory + File.separator + clusterID + "_transcripts" + FASTA_EXT;

                System.out.println("Assembling cluster `" + clusterID + "`...");

                boolean ok = overlapLayoutConcensus(readsPath, 
                        tmpPrefix, concensusPath, numThreads, stranded, minimapOptions, 
                        maxTipLength, 0.4f, 150, maxIndelSize, removeArtifacts, 1, usePacBioPreset);
                if (!ok) {
                    System.out.println("*** Error assembling cluster `" + clusterID + "`!!! ***");
                    errors.add(clusterID);
                    //@TODO return false;
                }
                else {
                    touch(stampFile);
                }
            }
        }
        
        boolean ok = errors.isEmpty();
        String assembledLongReadsConcatenated = assembledLongReadsDirectory + File.separator + "all_transcripts" + FASTA_EXT;
        String tmpPrefix = assembledLongReadsDirectory + File.separator + "all_transcripts_overlap";
        
        if (ok) {
            Pattern raconRcPattern = Pattern.compile("RC:i:(\\d+)");
            
            // combine assembly files
            FastaWriter fout = new FastaWriter(assembledLongReadsConcatenated, false);
            FastaReader fin;
            for (int clusterID : clusterIDs) {
                String clusterAssemblyPath = assembledLongReadsDirectory + File.separator + clusterID + "_transcripts" + FASTA_EXT;
                fin = new FastaReader(clusterAssemblyPath);
                while(fin.hasNext()) {
                    String[] nameCommentSeq = fin.nextWithComment();
                    String comment = nameCommentSeq[1];
                    String seq = nameCommentSeq[2];
                                        
                    if (writeUracil) {
                        seq = seq.replace('T', 'U');
                    }
                    
                    String length = Integer.toString(seq.length());
                    
                    String coverage = "1";
                    if (!comment.isEmpty()) {
                        Matcher m = raconRcPattern.matcher(comment);
                        if (m.find()) {
                            coverage = m.group(1);
                        }
                    }
                    
                    fout.write(txptNamePrefix + clusterID + "_" + nameCommentSeq[0] +
                            " l=" + length + " c=" + coverage,
                            seq);
                }
                fin.close();
            }
            fout.close();
        }
        else {
            System.out.println("Cannot assemble the following clusters:");
            Collections.sort(errors);
            System.out.println(Arrays.toString(errors.toArray()));
            return false;
        }
        
        System.out.println("Inter-cluster assembly...");
        ok = overlapLayout(assembledLongReadsConcatenated, tmpPrefix, assembledLongReadsCombined,
                numThreads, stranded, "-r " + Integer.toString(2*maxIndelSize),
                k, percentIdentity, minTranscriptLength, maxIndelSize, removeArtifacts, 1, usePacBioPreset);
        
        return ok;
    }
    */

    public boolean assembleClusteredLongReads(String readsPath, 
                                    String clusterdir, 
                                    String outFasta,
                                    boolean writeUracil,
                                    int numThreads,
                                    String minimapOptions,
                                    int minKmerCov,
                                    int maxEdgeClip,
                                    float minAlnId,
                                    int minOverlapMatches,
                                    String txptNamePrefix,
                                    boolean stranded,
                                    boolean removeArtifacts,
                                    int minSeqDepth,
                                    boolean usePacBioPreset,
                                    int maxMergedClusterSize,
                                    boolean forceOverwrite) throws IOException {
        
        System.out.println("Clustering reads...");
        int numClusters = clusteredOLC(readsPath, clusterdir,
                            numThreads, stranded, minimapOptions, maxEdgeClip,
                            minAlnId, minOverlapMatches, maxIndelSize, removeArtifacts,
                            minSeqDepth, usePacBioPreset, maxMergedClusterSize,
                            forceOverwrite);
        
        if (numClusters <= 0) {
            return false;
        }
        
        Pattern raconRcPattern = Pattern.compile("RC:i:(\\d+)");

        // combine assembly files
        System.out.println("Combining transcripts from " + numClusters + " clusters...");
        String catFasta = clusterdir + "_cat" + FASTA_EXT;
        String nrFasta = clusterdir + "_nr" + FASTA_EXT;
        String tmpPrefix = clusterdir + "_tmp";
        FastaWriter fout = new FastaWriter(catFasta, false);
        FastaReader fin;
//        int numTranscripts = 0;
        for (int clusterID = 1; clusterID<=numClusters; ++clusterID) {
            String clusterAssemblyPath = clusterdir + File.separator + clusterID + "_transcripts" + FASTA_EXT;
            fin = new FastaReader(clusterAssemblyPath);
            while(fin.hasNext()) {
//                ++numTranscripts;
                String[] nameCommentSeq = fin.nextWithComment();
                String comment = nameCommentSeq[1];
                String seq = nameCommentSeq[2];

                if (writeUracil) {
                    seq = seq.replace('T', 'U');
                }

                String length = Integer.toString(seq.length());

                String coverage = "1";
                if (!comment.isEmpty()) {
                    Matcher m = raconRcPattern.matcher(comment);
                    if (m.find()) {
                        coverage = m.group(1);
                    }
                }

                fout.write(txptNamePrefix + clusterID + "_" + nameCommentSeq[0] +
                        " l=" + length + " c=" + coverage, seq);
            }
            fin.close();
        }
        fout.close();
        
        System.out.println("Inter-cluster assembly...");
        boolean ok = overlapLayout(catFasta, tmpPrefix, nrFasta,
                numThreads, stranded, minimapOptions,
                maxEdgeClip, minAlnId, minOverlapMatches, maxIndelSize, false, 1, usePacBioPreset);

        if (!ok) {
            return false;
        }
        
        System.out.println("Polishing assembly...");
        boolean keepUnpolished = minSeqDepth <= 1;
        ok = mapAndConcensus(readsPath, nrFasta, tmpPrefix, outFasta, 
                numThreads, minimapOptions, usePacBioPreset, keepUnpolished);

        return ok;

//        System.out.println("Transcripts assembled: " + numTranscripts);
//        return true;
    }
    
    public boolean assembleUnclusteredLongReads(String readsPath, 
                                    String tmpPrefix, 
                                    String outFasta,
                                    int numThreads,
                                    String minimapOptions,
                                    int minKmerCov,
                                    int maxEdgeClip,
                                    float minAlnId,
                                    int minOverlapMatches,
                                    String txptNamePrefix,
                                    boolean stranded,
                                    boolean removeArtifacts,
                                    int minSeqDepth,
                                    boolean usePacBioPreset) throws IOException {
//        int maxEdgeClip = 100;
//        float minAlnId = 0.4f;
//        int minOverlapMatches = 200;
        
        boolean ok = overlapLayoutConcensus(readsPath, tmpPrefix, outFasta, 
                numThreads, stranded, minimapOptions, maxEdgeClip,
                minAlnId, minOverlapMatches, maxIndelSize, removeArtifacts,
                minSeqDepth, usePacBioPreset, false, true);
        
        return ok;
    }
    
    public static final int LENGTH_STRATUM_MIN_Q1_INDEX = 0;
    public static final int LENGTH_STRATUM_Q1_MED_INDEX = 1;
    public static final int LENGTH_STRATUM_MED_Q3_INDEX = 2;
    public static final int LENGTH_STRATUM_Q3_MAX_INDEX = 3;
    
    public static int getLongReadLengthStratumIndex(LengthStats stats, int testLen) {
        if (testLen < stats.q1) {
            return LENGTH_STRATUM_MIN_Q1_INDEX;
        }
        else if (testLen < stats.median) {
            return LENGTH_STRATUM_Q1_MED_INDEX;
        }
        else if (testLen < stats.q3) {
            return LENGTH_STRATUM_MED_Q3_INDEX;
        }
        else {
            return LENGTH_STRATUM_Q3_MAX_INDEX;
        }
    }
    
    private static final String[] LENGTH_STRATUM_NAMES = new String[]{"min_q1", "q1_med", "med_q3", "q3_max"};
    
    /*
    public class CorrectedLongReadsWriterWorker implements Runnable {
        private final ArrayBlockingQueue<Sequence> inputQueue;
        private final int maxSampleSize;
        private final int minSeqLen;
        private final FastaWriter[][] writers;
        private final FastaWriter repeatsWriter;
        private LengthStats sampleLengthStats = null;
        private boolean terminateWhenInputExhausts = false;
        private long numCorrected = 0;
        private boolean successful = false;
        private Exception exception = null;
        private LongReadCorrectionWorker[] workersToWait = null;
        
        public CorrectedLongReadsWriterWorker(ArrayBlockingQueue<Sequence> inputQueue, 
                FastaWriter[][] writers, FastaWriter repeatsWriter, int maxSampleSize, int minSeqLen) {
            this.inputQueue = inputQueue;
            this.writers = writers;
            this.repeatsWriter = repeatsWriter;
            this.maxSampleSize = maxSampleSize;
            this.minSeqLen = minSeqLen;
        }
                
        @Override
        public void run() {
            try {
                ArrayDeque<Sequence> sample = new ArrayDeque<>(maxSampleSize);

                while(!(terminateWhenInputExhausts && inputQueue.isEmpty())) {
                    Sequence seq = inputQueue.poll(1, TimeUnit.MILLISECONDS);
                    if (seq == null || seq.length < minSeqLen) {
                        continue;
                    }

                    sample.add(seq);

                    if (sample.size() == maxSampleSize) {
                        break;
                    }
                }

                int sampleSize = sample.size();
                int[] lengths = new int[sampleSize];
                int i = 0;
                for (Sequence seq : sample) {
                    lengths[i++] = seq.length;
                }
                                
                sampleLengthStats = getLengthStats(lengths);
                
                System.out.println("Corrected Read Lengths Sampling Distribution (n=" + sampleSize + ")");
                System.out.println("\tmin\tq1\tmed\tq3\tmax");
                System.out.println("\t" + sampleLengthStats.min + "\t" + 
                                            sampleLengthStats.q1 + "\t" +
                                            sampleLengthStats.median + "\t" +
                                            sampleLengthStats.q3 + "\t" +
                                            sampleLengthStats.max);

                // write the sample sequences to file
                for (Sequence seq : sample) {
                    int lengthStratumIndex = getLongReadLengthStratumIndex(sampleLengthStats, seq.length);
                    int covStatumIndex = getCoverageOrderOfMagnitude(seq.coverage);

                    //String header = Long.toString(++numCorrected) + " l=" + Integer.toString(seq.length) + " c=" + Float.toString(seq.coverage);
                    ++numCorrected;
                    String header = seq.name + " l=" + Integer.toString(seq.length) + " c=" + Float.toString(seq.coverage);

                    if (seq.isRepeat) {
                        repeatsWriter.write(header, seq.seq);
                    }
                    else {
                        writers[covStatumIndex][lengthStratumIndex].write(header, seq.seq);
                    }
                }

                // write the remaining sequences to file
                while(!(terminateWhenInputExhausts && areDependentWorkersDone() && inputQueue.isEmpty())) {
                    Sequence seq = inputQueue.poll(1, TimeUnit.MILLISECONDS);
                    if (seq == null || seq.length < minSeqLen) {
                        continue;
                    }

                    int lengthStratumIndex = getLongReadLengthStratumIndex(sampleLengthStats, seq.length);
                    int covStatumIndex = getCoverageOrderOfMagnitude(seq.coverage);

                    //String header = Long.toString(++numCorrected) + " l=" + Integer.toString(seq.length) + " c=" + Float.toString(seq.coverage);
                    ++numCorrected;
                    String header = seq.name + " l=" + Integer.toString(seq.length) + " c=" + Float.toString(seq.coverage);

                    if (seq.isRepeat) {
                        repeatsWriter.write(header, seq.seq);
                    }
                    else {
                        writers[covStatumIndex][lengthStratumIndex].write(header, seq.seq);
                    }
                }
                
                successful = true;
            
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
                exception = ex;
                successful = false;
            }
        }
        
        public void terminateWhenInputExhausts(LongReadCorrectionWorker[] workersToWait) {
            terminateWhenInputExhausts = true;
            this.workersToWait = workersToWait;
        }
        
        public boolean areDependentWorkersDone() {
            if (workersToWait == null) {
                return false;
            }
            else {
                int numRemaining = workersToWait.length;
                for (LongReadCorrectionWorker worker : workersToWait) {
                    if (worker.successful || worker.exception != null) {
                        --numRemaining;
                    }
                }
                
                return numRemaining <= 0;
            }
        }
        
        public boolean isSucessful() {
            return successful;
        }
        
        public Exception getExceptionCaught() {
            return exception;
        }
        
        public long getNumCorrected() {
            return numCorrected;
        }
        
        public LengthStats getSampleLengthStats() {
            return sampleLengthStats;
        }
    }
    */
    
    public class CorrectedLongReadsWriterWorker2 implements Runnable {
        private final ArrayBlockingQueue<Sequence> inputQueue;
        private final int maxSampleSize;
        private final int minSeqLen;
        private final FastaWriter longWriter;
        private final FastaWriter shortWriter;
        private final FastaWriter repeatsWriter;
        private LengthStats sampleLengthStats = null;
        private boolean terminateWhenInputExhausts = false;
        private long numCorrected = 0;
        private boolean successful = false;
        private Exception exception = null;
        private final boolean writeUracil;
        
        public CorrectedLongReadsWriterWorker2(ArrayBlockingQueue<Sequence> inputQueue, 
                FastaWriter longSeqWriter, FastaWriter shortSeqWriter, FastaWriter repeatsSeqWriter,
                int maxSampleSize, int minSeqLen, boolean writeUracil) {
            this.inputQueue = inputQueue;
            this.longWriter = longSeqWriter;
            this.shortWriter = shortSeqWriter;
            this.repeatsWriter = repeatsSeqWriter;
            this.maxSampleSize = maxSampleSize;
            this.minSeqLen = minSeqLen;
            this.writeUracil = writeUracil;
        }
                
        @Override
        public void run() {
            try {
                ArrayDeque<Sequence> sample = new ArrayDeque<>(maxSampleSize);

                while(true) {
                    Sequence seq = inputQueue.poll(100, TimeUnit.MILLISECONDS);
                    
                    if (seq == null) {
                        if (terminateWhenInputExhausts) {
                            break;
                        }
                        continue;
                    }
                    
                    sample.add(seq);

                    if (sample.size() == maxSampleSize) {
                        break;
                    }
                }

                int sampleSize = sample.size();
                int[] lengths = new int[sampleSize];
                int i = 0;
                for (Sequence seq : sample) {
                    lengths[i++] = seq.length;
                }
                                
                sampleLengthStats = getLengthStats(lengths);
                
                System.out.println("Corrected Read Lengths Sampling Distribution (n=" + sampleSize + ")");
                System.out.println("\tmin\tq1\tmed\tq3\tmax");
                System.out.println("\t" + sampleLengthStats.min + "\t" + 
                                            sampleLengthStats.q1 + "\t" +
                                            sampleLengthStats.median + "\t" +
                                            sampleLengthStats.q3 + "\t" +
                                            sampleLengthStats.max);

                // write the sample sequences to file
                for (Sequence seq : sample) {
                    ++numCorrected;
                    String header = seq.name + " l=" + Integer.toString(seq.length) + " c=" + Float.toString(seq.coverage);

                    if (writeUracil) {
                        seq.seq = seq.seq.replace('T', 'U');
                    }
                    
                    if (seq.isRepeat) {
                        repeatsWriter.write(header, seq.seq);
                    }
                    else if (seq.length >= minSeqLen) {
                        longWriter.write(header, seq.seq);
                    }
                    else {
                        shortWriter.write(header, seq.seq);
                    }
                }

                // write the remaining sequences to file
                while(true) {
                    Sequence seq = inputQueue.poll(100, TimeUnit.MILLISECONDS);
                  
                    if (seq == null) {
                        if (terminateWhenInputExhausts) {
                            break;
                        }
                        continue;
                    }
                    
                    //String header = Long.toString(++numCorrected) + " l=" + Integer.toString(seq.length) + " c=" + Float.toString(seq.coverage);
                    ++numCorrected;
                    String header = seq.name + " l=" + Integer.toString(seq.length) + " c=" + Float.toString(seq.coverage);

                    if (writeUracil) {
                        seq.seq = seq.seq.replace('T', 'U');
                    }
                    
                    if (seq.isRepeat) {
                        repeatsWriter.write(header, seq.seq);
                    }
                    else if (seq.length >= minSeqLen) {
                        longWriter.write(header, seq.seq);
                    }
                    else {
                        shortWriter.write(header, seq.seq);
                    }
                }
                
                successful = true;
            
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
                exception = ex;
                successful = false;
            }
        }
        
        public void terminateWhenInputExhausts() {
            terminateWhenInputExhausts = true;
        }
        
        public boolean isSucessful() {
            return successful;
        }
        
        public Exception getExceptionCaught() {
            return exception;
        }
        
        public long getNumCorrected() {
            return numCorrected;
        }
        
        public LengthStats getSampleLengthStats() {
            return sampleLengthStats;
        }
    }
    
    public static class Sequence {
        String name;
        String seq;
        int length;
        float coverage;
        boolean isRepeat;
        
        public Sequence(String name, String seq, int length, float coverage, boolean isRepeat) {
            this.name = name;
            this.seq = seq;
            this.length = length;
            this.coverage = coverage;
            this.isRepeat = isRepeat;
        }
    }
    
    public class LongReadCorrectionWorker implements Runnable {
        private final FastxSequenceIterator itr;
        private final ArrayBlockingQueue<Sequence> outputQueue;
        private boolean successful = false;
        private Exception exception = null;
        private final int maxErrCorrItr;
        private final int minKmerCov;
        private final int minNumSolidKmers;
        private final boolean reverseComplement;
        private boolean trimArtifact = false;
        private long numReads = 0;
        private long numArtifacts = 0;

        public LongReadCorrectionWorker(FastxSequenceIterator itr,
                                        ArrayBlockingQueue<Sequence> outputQueue,
                                        int maxErrCorrItr, int minKmerCov, int minNumSolidKmers,
                                        boolean reverseComplement, boolean trimArtifact) {
            this.itr = itr;
            this.outputQueue = outputQueue;
            this.maxErrCorrItr = maxErrCorrItr;
            this.minKmerCov = minKmerCov;
            this.minNumSolidKmers = minNumSolidKmers;
            this.reverseComplement = reverseComplement;
            this.trimArtifact = trimArtifact;
        }
        
        @Override
        public void run() {
            //boolean stranded = graph.isStranded();
            
            try {
                String[] nameSeqPair;
                while((nameSeqPair = itr.next()) != null) {
                    ++numReads;
                    String seq = reverseComplement ? reverseComplement(nameSeqPair[1]) : nameSeqPair[1];
                    
                    ArrayList<Kmer> kmers = graph.getKmers(seq);
                    
                    ArrayList<Kmer> correctedKmers = correctLongSequence(kmers, 
                                                                        graph, 
                                                                        maxErrCorrItr, 
                                                                        maxCovGradient, 
                                                                        lookahead, 
                                                                        maxIndelSize, 
                                                                        percentIdentity, 
                                                                        minKmerCov,
                                                                        minNumSolidKmers,
                                                                        false);
                                        
                    if (correctedKmers != null && !correctedKmers.isEmpty()) {
                        if (trimArtifact) {
                            ArrayList<Kmer> trimmed = trimReverseComplementArtifact(correctedKmers,
                                                graph, strandSpecific, 150, maxIndelSize, percentIdentity, maxCovGradient);
                            if (!trimmed.isEmpty() && trimmed.size() < correctedKmers.size()) {
                                ++numArtifacts;
                                correctedKmers = trimmed;
                            }
                        }
                        
                        if (!correctedKmers.isEmpty()) {
                            //float cov = getMinMedianKmerCoverage(correctedKmers, 200);
                            float cov = getMedianKmerCoverage(correctedKmers);
                            boolean isRepeat = isRepeatSequence(correctedKmers, k, 1f/3f) || isLowComplexity(correctedKmers, k, 1f/3f);
                            
                            seq = graph.assemble(correctedKmers);
                            
                            int seqLength = seq.length();
                            if (!isRepeat && compressHomoPolymers(seq).length() < 1f/3f * seqLength) {
                                isRepeat = true;
                            }

                            /*
                            int halflen = seqLength/2;                            
                            String prefix = "";

                            if (!isRepeat) {
                                if (stranded) {
                                    // find polyA tail and trim trailing sequence
                                    int[] region = getPolyATailRegion(seq, 100, 17, 15, 2);
                                    if (region != null) {
                                        int start = region[0];
                                        int end = region[1];
                                        if (start >= halflen) {
                                            seq = seq.substring(0, start) + "A".repeat(end-start+1);
                                        }
                                    }
                                }
                                else {
                                    // find polyA tail and trim trailing sequence
                                    int[] regionA = getPolyATailRegion(seq, 100, 17, 15, 2);
                                    int[] regionT = getPolyTHeadRegion(seq, 100, 17, 15, 2);
                                    if (regionA != null && regionT == null) {
                                        int start = regionA[0];
                                        int end = regionA[1];
                                        if (start >= halflen) {
                                            seq = seq.substring(0, start) + "A".repeat(end-start+1);
                                        }
                                    }
                                    else if (regionT != null && regionA == null) {
                                        int start = regionT[0];
                                        int end = regionT[1];
                                        if (start >= 0 && end <= halflen) {
                                            seq = reverseComplement(seq.substring(end+1)) + "A".repeat(end-start+1);
                                        }
                                    }
                                    else if (regionA != null && regionT != null) {
                                        int startA = regionA[0];
                                        int endA = regionA[1];
                                        int startT = regionT[0];
                                        int endT = regionT[1];
                                        boolean isPolyA = startA >= halflen;
                                        boolean isPolyT = startT >= 0 && endT <= halflen;
                                        if (isPolyA && !isPolyT) {
                                            seq = seq.substring(0, startA) + "A".repeat(endA-startA+1);
                                        }
                                        else if (isPolyT && !isPolyA) {
                                            seq = reverseComplement(seq.substring(endT+1)) + "A".repeat(endT-startT+1);
                                        }
                                        else if (isPolyA && isPolyT){
                                            int midIndex = (endT+1+startA)/2;
                                            String left = reverseComplement(seq.substring(endT+1, midIndex));
                                            String right = seq.substring(midIndex, startA);
                                            if (getPercentIdentity(left, right) >= percentIdentity) {
                                                seq = right + "A".repeat(Math.min(endA-startA+1, endT-startT+1));
                                                prefix = "TSA_";
                                            }
                                            else {
                                                int numA = 0;
                                                for (int i=startA; i<=endA; ++i) {
                                                    if (seq.charAt(i) == 'A') {
                                                        ++numA;
                                                    }
                                                }

                                                int numT = 0;
                                                for (int i=startT; i<=endT; ++i) {
                                                    if (seq.charAt(i) == 'T') {
                                                        ++numT;
                                                    }
                                                }

                                                if (numA >= numT) {
                                                    seq = seq.substring(0, startA) + "A".repeat(endA-startA+1);
                                                }
                                                else {
                                                    seq = reverseComplement(seq.substring(endT+1)) + "A".repeat(endT-startT+1);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            */
                            
                            outputQueue.put(new Sequence(nameSeqPair[0], seq, seq.length(), cov, isRepeat));
                        }
                    }
                }
                successful = true;
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
                ex.printStackTrace();
                exception = ex;
                successful = false;
                return;
            }
        }
        
        public boolean isSucessful() {
            return successful;
        }
        
        public Exception getExceptionCaught() {
            return exception;
        }
    }
    
    /*
    public long correctLongReadsMultithreaded(String[] inputFastxPaths,
                                                FastaWriter[][] outFastaWriters,
                                                FastaWriter repeatsOutFastaWriter,
                                                int minKmerCov,
                                                int maxErrCorrItr,
                                                int numThreads,
                                                int maxSampleSize,
                                                int minSeqLen,
                                                boolean reverseComplement,
                                                boolean trimArtifact) throws InterruptedException, IOException, Exception {
        long numReads = 0;
        
        MyExecutorService service = new MyExecutorService(numThreads+1, numThreads+1);
        
        int maxQueueSize = 100;
//        ArrayBlockingQueue<String[]> inputQueue = new ArrayBlockingQueue<>(maxQueueSize);
        ArrayBlockingQueue<Sequence> outputQueue = new ArrayBlockingQueue<>(maxQueueSize);
                
        int numCorrectionWorkers = numThreads;
        LongReadCorrectionWorker[] correctionWorkers = new LongReadCorrectionWorker[numCorrectionWorkers];
        int minNumSolidKmers = 5;
        FastxSequenceIterator itr = new FastxSequenceIterator(inputFastxPaths);
        
        for (int i=0; i<numCorrectionWorkers; ++i) {
            LongReadCorrectionWorker worker = new LongReadCorrectionWorker(itr, outputQueue, maxErrCorrItr, minKmerCov, minNumSolidKmers, reverseComplement, trimArtifact);
            correctionWorkers[i] = worker;
            service.submit(worker);
        }
        
        //FastaWriter writer = new FastaWriter(outFasta, true);
        CorrectedLongReadsWriterWorker writerWorker = new CorrectedLongReadsWriterWorker(outputQueue, outFastaWriters, repeatsOutFastaWriter, maxSampleSize, minSeqLen);
        service.submit(writerWorker);
        
//        for (FastxSequenceIterator itr = new FastxSequenceIterator(inputFastxPaths); itr.hasNext() ; ++numReads) {
//            String[] seq = itr.nextWithName();
//            if (seq[1].length() >= minSeqLen) {
//                inputQueue.put(seq);
//            }
//        }
        
        for (LongReadCorrectionWorker worker : correctionWorkers) {
            worker.terminateWhenInputExhausts();
        }
        
        writerWorker.terminateWhenInputExhausts(correctionWorkers);
        
        service.terminate();
        
        // check for errors
        if (!writerWorker.isSucessful()) {
            throw writerWorker.getExceptionCaught();
        }
        
        long numArtifacts = 0;
        for (LongReadCorrectionWorker worker : correctionWorkers) {
            if (!worker.isSucessful()) {
                throw worker.getExceptionCaught();
            }
            numArtifacts += worker.numArtifacts;
        }
        
        assert outputQueue.isEmpty();
        
        System.out.println("Parsed " + NumberFormat.getInstance().format(numReads) + " sequences.");
        long numCorrected = writerWorker.getNumCorrected();
        long numDiscarded = numReads - numCorrected;
        System.out.println("\tKept:      " + NumberFormat.getInstance().format(numCorrected) + "(" + numCorrected * 100f/numReads + "%)");
        System.out.println("\tDiscarded: " + NumberFormat.getInstance().format(numDiscarded) + "(" + numDiscarded * 100f/numReads + "%)");
        
        if (numArtifacts > 0) { 
            System.out.println("\tArtifacts: " + NumberFormat.getInstance().format(numArtifacts) + "(" + numArtifacts * 100f/numReads + "%)");
        }
        
        return numReads;
    }
    */
    
    public long correctLongReadsMultithreaded(String[] inputFastxPaths,
                                                FastaWriter longSeqWriter,
                                                FastaWriter shortSeqWriter,
                                                FastaWriter repeatsSeqWriter,
                                                int minKmerCov,
                                                int maxErrCorrItr,
                                                int numThreads,
                                                int maxSampleSize,
                                                int minSeqLen,
                                                boolean reverseComplement,
                                                boolean trimArtifact,
                                                boolean writeUracil) throws InterruptedException, IOException, Exception {

        long numReads = 0;
        ArrayBlockingQueue<Sequence> outputQueue = new ArrayBlockingQueue<>(maxSampleSize);
                
        LongReadCorrectionWorker[] correctionWorkers = new LongReadCorrectionWorker[numThreads];
        Thread[] threads = new Thread[numThreads];
        
        int minNumSolidKmers = Math.max(1, (int) Math.floor(minSeqLen * percentIdentity) - k + 1);
        FastxSequenceIterator itr = new FastxSequenceIterator(inputFastxPaths);
        
        for (int i=0; i<numThreads; ++i) {
            LongReadCorrectionWorker worker = new LongReadCorrectionWorker(itr, outputQueue, maxErrCorrItr, minKmerCov,
                    minNumSolidKmers, reverseComplement, trimArtifact);
            correctionWorkers[i] = worker;
            threads[i] = new Thread(worker);
            threads[i].start();
        }
        System.out.println("Initialized " + numThreads + " worker(s).");
        
        CorrectedLongReadsWriterWorker2 writerWorker = new CorrectedLongReadsWriterWorker2(outputQueue, 
                longSeqWriter, shortSeqWriter, repeatsSeqWriter,
                maxSampleSize, minSeqLen, writeUracil);
        Thread writerThread = new Thread(writerWorker);
        writerThread.start();
        System.out.println("Initialized writer.");
        
        for (Thread t : threads) {
            t.join();
        }

        writerWorker.terminateWhenInputExhausts();
        writerThread.join();
        
        // check for errors
        if (!writerWorker.isSucessful()) {
            throw writerWorker.getExceptionCaught();
        }
        
        long numArtifacts = 0;
        for (LongReadCorrectionWorker worker : correctionWorkers) {
            if (!worker.isSucessful()) {
                throw worker.getExceptionCaught();
            }
            numArtifacts += worker.numArtifacts;
            numReads += worker.numReads;
        }
        
        assert outputQueue.isEmpty();
        
        System.out.println("Parsed " + NumberFormat.getInstance().format(numReads) + " sequences.");
        long numCorrected = writerWorker.getNumCorrected();
        long numDiscarded = numReads - numCorrected;
        System.out.println("\tKept:      " + NumberFormat.getInstance().format(numCorrected) + "\t(" + numCorrected * 100f/numReads + "%)");
        System.out.println("\tDiscarded: " + NumberFormat.getInstance().format(numDiscarded) + "\t(" + numDiscarded * 100f/numReads + "%)");
        
        if (numArtifacts > 0) { 
            System.out.println("\tArtifacts: " + NumberFormat.getInstance().format(numArtifacts) + "\t(" + numArtifacts * 100f/numReads + "%)");
        }
        
        return numCorrected;
    }
    
    public String polishSequence(String seq, int maxErrCorrItr, int minKmerCov, int minNumSolidKmers) {
        ArrayList<Kmer> kmers = graph.getKmers(seq);

        ArrayList<Kmer> correctedKmers = correctLongSequence(kmers, 
                                                            graph, 
                                                            maxErrCorrItr, 
                                                            maxCovGradient, 
                                                            lookahead, 
                                                            maxIndelSize, 
                                                            percentIdentity, 
                                                            minKmerCov,
                                                            minNumSolidKmers,
                                                            false);
        
        if (correctedKmers != null && !correctedKmers.isEmpty()) {
            return graph.assemble(correctedKmers);
        }
        
        return null;
    }

    private class FragmentWriters {
        boolean assemblePolyaTails = false;
        NucleotideBitsWriter[] longFragmentsOut, shortFragmentsOut, unconnectedReadsOut, longPolyaFragmentsOut, shortPolyaFragmentsOut, unconnectedPolyaReadsOut;
        NucleotideBitsWriter longSingletonsOut, shortSingletonsOut, unconnectedSingletonsOut, longPolyaSingletonsOut, shortPolyaSingletonsOut, unconnectedPolyaSingletonsOut;
        
        private FragmentWriters(FragmentPaths fragPaths, boolean assemblePolyaTails) throws IOException {
            this.assemblePolyaTails = assemblePolyaTails;
            longFragmentsOut = new NucleotideBitsWriter[]{new NucleotideBitsWriter(fragPaths.longFragmentsPaths[0], true),
                                                new NucleotideBitsWriter(fragPaths.longFragmentsPaths[1], true),
                                                new NucleotideBitsWriter(fragPaths.longFragmentsPaths[2], true),
                                                new NucleotideBitsWriter(fragPaths.longFragmentsPaths[3], true),
                                                new NucleotideBitsWriter(fragPaths.longFragmentsPaths[4], true),
                                                new NucleotideBitsWriter(fragPaths.longFragmentsPaths[5], true)};

            shortFragmentsOut = new NucleotideBitsWriter[]{new NucleotideBitsWriter(fragPaths.shortFragmentsPaths[0], true),
                                                new NucleotideBitsWriter(fragPaths.shortFragmentsPaths[1], true),
                                                new NucleotideBitsWriter(fragPaths.shortFragmentsPaths[2], true),
                                                new NucleotideBitsWriter(fragPaths.shortFragmentsPaths[3], true),
                                                new NucleotideBitsWriter(fragPaths.shortFragmentsPaths[4], true),
                                                new NucleotideBitsWriter(fragPaths.shortFragmentsPaths[5], true)};

            unconnectedReadsOut = new NucleotideBitsWriter[]{new NucleotideBitsWriter(fragPaths.unconnectedReadsPaths[0], true),
                                                    new NucleotideBitsWriter(fragPaths.unconnectedReadsPaths[1], true),
                                                    new NucleotideBitsWriter(fragPaths.unconnectedReadsPaths[2], true),
                                                    new NucleotideBitsWriter(fragPaths.unconnectedReadsPaths[3], true),
                                                    new NucleotideBitsWriter(fragPaths.unconnectedReadsPaths[4], true),
                                                    new NucleotideBitsWriter(fragPaths.unconnectedReadsPaths[5], true)};

            longSingletonsOut = new NucleotideBitsWriter(fragPaths.longSingletonsPath, true);
            shortSingletonsOut = new NucleotideBitsWriter(fragPaths.shortSingletonsPath, true);
            unconnectedSingletonsOut = new NucleotideBitsWriter(fragPaths.unconnectedSingletonsPath, true);

            if (assemblePolyaTails) {
                longPolyaFragmentsOut = new NucleotideBitsWriter[]{new NucleotideBitsWriter(fragPaths.longPolyaFragmentsPaths[0], true),
                                                            new NucleotideBitsWriter(fragPaths.longPolyaFragmentsPaths[1], true),
                                                            new NucleotideBitsWriter(fragPaths.longPolyaFragmentsPaths[2], true),
                                                            new NucleotideBitsWriter(fragPaths.longPolyaFragmentsPaths[3], true),
                                                            new NucleotideBitsWriter(fragPaths.longPolyaFragmentsPaths[4], true),
                                                            new NucleotideBitsWriter(fragPaths.longPolyaFragmentsPaths[5], true)};

                shortPolyaFragmentsOut = new NucleotideBitsWriter[]{new NucleotideBitsWriter(fragPaths.shortPolyaFragmentsPaths[0], true),
                                                            new NucleotideBitsWriter(fragPaths.shortPolyaFragmentsPaths[1], true),
                                                            new NucleotideBitsWriter(fragPaths.shortPolyaFragmentsPaths[2], true),
                                                            new NucleotideBitsWriter(fragPaths.shortPolyaFragmentsPaths[3], true),
                                                            new NucleotideBitsWriter(fragPaths.shortPolyaFragmentsPaths[4], true),
                                                            new NucleotideBitsWriter(fragPaths.shortPolyaFragmentsPaths[5], true)};

                unconnectedPolyaReadsOut = new NucleotideBitsWriter[]{new NucleotideBitsWriter(fragPaths.unconnectedPolyaReadsPaths[0], true),
                                                            new NucleotideBitsWriter(fragPaths.unconnectedPolyaReadsPaths[1], true),
                                                            new NucleotideBitsWriter(fragPaths.unconnectedPolyaReadsPaths[2], true),
                                                            new NucleotideBitsWriter(fragPaths.unconnectedPolyaReadsPaths[3], true),
                                                            new NucleotideBitsWriter(fragPaths.unconnectedPolyaReadsPaths[4], true),
                                                            new NucleotideBitsWriter(fragPaths.unconnectedPolyaReadsPaths[5], true)};

                longPolyaSingletonsOut = new NucleotideBitsWriter(fragPaths.longPolyaSingletonsPath, true);
                shortPolyaSingletonsOut = new NucleotideBitsWriter(fragPaths.shortPolyaSingletonsPath, true);
                unconnectedPolyaSingletonsOut = new NucleotideBitsWriter(fragPaths.unconnectedPolyaSingletonsPath, true);
            }
        }
        
        public void writeUnconnected(String seq, float fragMinCov) throws IOException {
            boolean isPolya = assemblePolyaTails && polyATailPattern.matcher(seq).matches();

            if (fragMinCov == 1) {
                if (isPolya) {
                    unconnectedPolyaSingletonsOut.write(seq);
                }
                else {
                    unconnectedSingletonsOut.write(seq);
                }
            }
            else {
                int m = getCoverageOrderOfMagnitude(fragMinCov);

                if (isPolya) {
                    unconnectedPolyaReadsOut[m].write(seq);
                }
                else {
                    unconnectedReadsOut[m].write(seq);
                }
            }
        }
        
        public void writeShort(String seq, float fragMinCov) throws IOException {
            boolean isPolya = assemblePolyaTails && polyATailPattern.matcher(seq).matches();

            if (fragMinCov == 1) {
                if (isPolya) {
                    shortPolyaSingletonsOut.write(seq);
                }
                else {
                    shortSingletonsOut.write(seq);
                }
            }
            else {
                int m = getCoverageOrderOfMagnitude(fragMinCov);
                if (isPolya) {
                    shortPolyaFragmentsOut[m].write(seq);
                }
                else {
                    shortFragmentsOut[m].write(seq);
                }
            }
        }
    
        public void writeLong(String seq, float fragMinCov) throws IOException {
            boolean isPolya = assemblePolyaTails && polyATailPattern.matcher(seq).matches();

            if (fragMinCov == 1) {
                if (isPolya) {
                    longPolyaSingletonsOut.write(seq);
                }
                else {
                    longSingletonsOut.write(seq);
                }
            }
            else {
                int m = getCoverageOrderOfMagnitude(fragMinCov);

                if (isPolya) {
                    longPolyaFragmentsOut[m].write(seq);
                }
                else {
                    longFragmentsOut[m].write(seq);
                }
            }
        }
        
        public void closeAll() throws IOException {
            for (NucleotideBitsWriter f : longFragmentsOut) {
                f.close();
            }
            
            for (NucleotideBitsWriter f : shortFragmentsOut) {
                f.close();
            }
            
            for (NucleotideBitsWriter f : unconnectedReadsOut) {
                f.close();
            }
            
            longSingletonsOut.close();
            shortSingletonsOut.close();
            unconnectedSingletonsOut.close();
            
            if (assemblePolyaTails) {
                for (NucleotideBitsWriter f : longPolyaFragmentsOut) {
                    f.close();
                }

                for (NucleotideBitsWriter f : shortPolyaFragmentsOut) {
                    f.close();
                }

                for (NucleotideBitsWriter f : unconnectedPolyaReadsOut) {
                    f.close();
                }

                longPolyaSingletonsOut.close();
                shortPolyaSingletonsOut.close();
                unconnectedPolyaSingletonsOut.close();
            }
        }
    }
    
    private class FragmentWriterWorker implements Runnable {
        private int shortestFragmentLengthAllowed;
        private ArrayBlockingQueue<Fragment> fragments;
        private FragmentPaths outPaths;
        private FragmentWriters writers;
        private boolean keepGoing = true;
        private long readPairsConnected = 0;
        private long readPairsNotConnected = 0;
        
        private FragmentWriterWorker(ArrayBlockingQueue<Fragment> fragments, FragmentPaths outPaths, boolean assemblePolyaTails, int shortestFragmentLengthAllowed) throws IOException {
            this.fragments = fragments;
            this.outPaths = outPaths;
            this.writers = new FragmentWriters(outPaths, assemblePolyaTails);
            this.shortestFragmentLengthAllowed = shortestFragmentLengthAllowed;
        }
        
        @Override
        public void run() {
            try {
                while (true) {
                    Fragment frag = fragments.poll(10, TimeUnit.MICROSECONDS);

                    if (frag == null) {
                        if (!keepGoing) {
                            break;
                        }
                    }
                    else {
                        float minCov = frag.minCov;
                        
                        if (frag.isUnconnectedRead) {
                            ++readPairsNotConnected;

                            String seq = frag.left;
                            if (seq != null && seq.length() >= k) {
                                writers.writeUnconnected(seq, minCov);
                            }

                            seq = frag.right;
                            if (seq != null && seq.length() >= k) {
                                writers.writeUnconnected(seq, minCov);
                            }
                        }
                        else {
                            ++readPairsConnected;

                            int fragLen = frag.kmers.size()+ k - 1; // not using frag.length because it is the original length and frag may be extended
                            
                            if (fragLen >= shortestFragmentLengthAllowed && minCov > 0) {
                                ArrayList<Kmer> fragKmers = frag.kmers;
                                boolean hasAllKmers = lookupAndAddAllKmers(screeningBf, fragKmers);

                                if (fragLen < longFragmentLengthThreshold) {
                                    if (!hasAllKmers) {
                                        writers.writeShort(graph.assemble(fragKmers), minCov);
                                    }
                                }
                                else {
                                    boolean hasAllKmerPairs = graph.lookupAndAddAllPairedKmers(fragKmers);
                                    if (!hasAllKmers || !hasAllKmerPairs) {
                                        writers.writeLong(graph.assemble(fragKmers), minCov);
                                    }
                                }
                            }
                        }
                    }
                }
                
                writers.closeAll();
            }
            catch(Exception ex) {
                ex.printStackTrace();
                throw new RuntimeException(ex);
            }
        }
        
        public void stopWhenEmpty() {
            keepGoing = false;
        }
        
        public long getNumConnected() {
            return readPairsConnected;
        }
        
        public long getNumUnconnected() {
            return readPairsNotConnected;
        }
    }    
    
    private static class FragmentPaths {
        String[] longFragmentsPaths;
        String[] shortFragmentsPaths;
        String[] unconnectedReadsPaths;
        String longSingletonsPath;
        String shortSingletonsPath;
        String unconnectedSingletonsPath;
        String[] longPolyaFragmentsPaths;
        String[] shortPolyaFragmentsPaths;
        String[] unconnectedPolyaReadsPaths;
        String longPolyaSingletonsPath;
        String shortPolyaSingletonsPath;
        String unconnectedPolyaSingletonsPath;
        
        public FragmentPaths(String outdir, String name) {
            String longFragmentsFastaPrefix =      outdir + File.separator + name + ".fragments.long.";
            String shortFragmentsFastaPrefix =     outdir + File.separator + name + ".fragments.short.";
            String unconnectedReadsFastaPrefix =   outdir + File.separator + name + ".unconnected.";
            String longPolyaFragmentsFastaPrefix =      outdir + File.separator + name + ".fragments.polya.long.";
            String shortPolyaFragmentsFastaPrefix =     outdir + File.separator + name + ".fragments.polya.short.";
            String unconnectedPolyaReadsFastaPrefix =   outdir + File.separator + name + ".unconnected.polya.";
                        
            longFragmentsPaths = new String[]{longFragmentsFastaPrefix + COVERAGE_ORDER[0] + NBITS_EXT,
                                                        longFragmentsFastaPrefix + COVERAGE_ORDER[1] + NBITS_EXT,
                                                        longFragmentsFastaPrefix + COVERAGE_ORDER[2] + NBITS_EXT,
                                                        longFragmentsFastaPrefix + COVERAGE_ORDER[3] + NBITS_EXT,
                                                        longFragmentsFastaPrefix + COVERAGE_ORDER[4] + NBITS_EXT,
                                                        longFragmentsFastaPrefix + COVERAGE_ORDER[5] + NBITS_EXT};

            shortFragmentsPaths = new String[]{shortFragmentsFastaPrefix + COVERAGE_ORDER[0] + NBITS_EXT,
                                                        shortFragmentsFastaPrefix + COVERAGE_ORDER[1] + NBITS_EXT,
                                                        shortFragmentsFastaPrefix + COVERAGE_ORDER[2] + NBITS_EXT,
                                                        shortFragmentsFastaPrefix + COVERAGE_ORDER[3] + NBITS_EXT,
                                                        shortFragmentsFastaPrefix + COVERAGE_ORDER[4] + NBITS_EXT,
                                                        shortFragmentsFastaPrefix + COVERAGE_ORDER[5] + NBITS_EXT};

            unconnectedReadsPaths = new String[]{unconnectedReadsFastaPrefix + COVERAGE_ORDER[0] + NBITS_EXT,
                                                            unconnectedReadsFastaPrefix + COVERAGE_ORDER[1] + NBITS_EXT,
                                                            unconnectedReadsFastaPrefix + COVERAGE_ORDER[2] + NBITS_EXT,
                                                            unconnectedReadsFastaPrefix + COVERAGE_ORDER[3] + NBITS_EXT,
                                                            unconnectedReadsFastaPrefix + COVERAGE_ORDER[4] + NBITS_EXT,
                                                            unconnectedReadsFastaPrefix + COVERAGE_ORDER[5] + NBITS_EXT};

            longPolyaFragmentsPaths = new String[]{longPolyaFragmentsFastaPrefix + COVERAGE_ORDER[0] + NBITS_EXT,
                                                            longPolyaFragmentsFastaPrefix + COVERAGE_ORDER[1] + NBITS_EXT,
                                                            longPolyaFragmentsFastaPrefix + COVERAGE_ORDER[2] + NBITS_EXT,
                                                            longPolyaFragmentsFastaPrefix + COVERAGE_ORDER[3] + NBITS_EXT,
                                                            longPolyaFragmentsFastaPrefix + COVERAGE_ORDER[4] + NBITS_EXT,
                                                            longPolyaFragmentsFastaPrefix + COVERAGE_ORDER[5] + NBITS_EXT};

            shortPolyaFragmentsPaths = new String[]{shortPolyaFragmentsFastaPrefix + COVERAGE_ORDER[0] + NBITS_EXT,
                                                                shortPolyaFragmentsFastaPrefix + COVERAGE_ORDER[1] + NBITS_EXT,
                                                                shortPolyaFragmentsFastaPrefix + COVERAGE_ORDER[2] + NBITS_EXT,
                                                                shortPolyaFragmentsFastaPrefix + COVERAGE_ORDER[3] + NBITS_EXT,
                                                                shortPolyaFragmentsFastaPrefix + COVERAGE_ORDER[4] + NBITS_EXT,
                                                                shortPolyaFragmentsFastaPrefix + COVERAGE_ORDER[5] + NBITS_EXT};

            unconnectedPolyaReadsPaths = new String[]{unconnectedPolyaReadsFastaPrefix + COVERAGE_ORDER[0] + NBITS_EXT,
                                                                unconnectedPolyaReadsFastaPrefix + COVERAGE_ORDER[1] + NBITS_EXT,
                                                                unconnectedPolyaReadsFastaPrefix + COVERAGE_ORDER[2] + NBITS_EXT,
                                                                unconnectedPolyaReadsFastaPrefix + COVERAGE_ORDER[3] + NBITS_EXT,
                                                                unconnectedPolyaReadsFastaPrefix + COVERAGE_ORDER[4] + NBITS_EXT,
                                                                unconnectedPolyaReadsFastaPrefix + COVERAGE_ORDER[5] + NBITS_EXT};
        
            longSingletonsPath = longFragmentsFastaPrefix + "01" + NBITS_EXT;
            shortSingletonsPath = shortFragmentsFastaPrefix + "01" + NBITS_EXT;
            unconnectedSingletonsPath = unconnectedReadsFastaPrefix + "01" + NBITS_EXT;
        
            longPolyaSingletonsPath = longPolyaFragmentsFastaPrefix + "01" + NBITS_EXT;
            shortPolyaSingletonsPath = shortPolyaFragmentsFastaPrefix + "01" + NBITS_EXT;
            unconnectedPolyaSingletonsPath = unconnectedPolyaReadsFastaPrefix + "01" + NBITS_EXT;
        }
        
        public ArrayList asList(boolean assemblePolya) {
            ArrayList<String> paths = new ArrayList<>(longFragmentsPaths.length + shortFragmentsPaths.length + unconnectedReadsPaths.length + 3);
            paths.addAll(Arrays.asList(longFragmentsPaths));
            paths.addAll(Arrays.asList(shortFragmentsPaths));
            paths.addAll(Arrays.asList(unconnectedReadsPaths));
            paths.add(longSingletonsPath);
            paths.add(shortSingletonsPath);
            paths.add(unconnectedSingletonsPath);

            if (assemblePolya) {
                paths.addAll(Arrays.asList(longPolyaFragmentsPaths));
                paths.addAll(Arrays.asList(shortPolyaFragmentsPaths));
                paths.addAll(Arrays.asList(unconnectedPolyaReadsPaths));
                paths.add(longPolyaSingletonsPath);
                paths.add(shortPolyaSingletonsPath);
                paths.add(unconnectedPolyaSingletonsPath);
            }
            
            return paths;
        }
        
        public void deleteAll() throws IOException {
            FileSystem sys = FileSystems.getDefault();
            
            for (String path : longFragmentsPaths) {
                Files.deleteIfExists(sys.getPath(path));
            }
            
            for (String path : shortFragmentsPaths) {
                Files.deleteIfExists(sys.getPath(path));
            }
            
            for (String path : unconnectedReadsPaths) {
                Files.deleteIfExists(sys.getPath(path));
            }
            
            for (String path : longPolyaFragmentsPaths) {
                Files.deleteIfExists(sys.getPath(path));
            }
            
            for (String path : shortPolyaFragmentsPaths) {
                Files.deleteIfExists(sys.getPath(path));
            }
            
            for (String path : unconnectedPolyaReadsPaths) {
                Files.deleteIfExists(sys.getPath(path));
            }
            
            Files.deleteIfExists(sys.getPath(longSingletonsPath));
            Files.deleteIfExists(sys.getPath(shortSingletonsPath));
            Files.deleteIfExists(sys.getPath(unconnectedSingletonsPath));
            Files.deleteIfExists(sys.getPath(longPolyaSingletonsPath));
            Files.deleteIfExists(sys.getPath(shortPolyaSingletonsPath));
            Files.deleteIfExists(sys.getPath(unconnectedPolyaSingletonsPath));
        }
    }
    
    /*
    public class ReadPairsFactoryWorker implements Runnable {
        private FastxFilePair[] fastxPairs;
        private ArrayBlockingQueue<PairedReadSegments> readPairsQueue;
        private long numParsed = 0;
        private boolean stopped = false;
        
        public ReadPairsFactoryWorker(FastxFilePair[] fastxPairs,
                ArrayBlockingQueue<PairedReadSegments> readPairsQueue) {
            this.fastxPairs = fastxPairs;
            this.readPairsQueue = readPairsQueue;
        }
        
        @Override
        public void run() {
            try {
                FastxPairSequenceIterator rin = new FastxPairSequenceIterator(fastxPairs, seqPattern, qualPatternFrag);
                while (rin.hasNext()) {
                    readPairsQueue.put(rin.next());
                    ++numParsed;
                }
                stopped = true;
            }
            catch (Exception ex) {
                stopped = true;
                ex.printStackTrace();
                throw new RuntimeException(ex);
            }
        }
    }
    */
    
    public int[] assembleFragmentsMultiThreaded(FastxFilePair[] fastxPairs, 
                                                FragmentPaths fragPaths,
                                                int bound,
                                                int minOverlap,
                                                int sampleSize, 
                                                int numThreads, 
                                                int maxErrCorrIterations,
                                                boolean extendFragments,
                                                int minKmerCov,
                                                boolean keepArtifact) throws FileFormatException, IOException, InterruptedException {
        
        if (dbgFPR <= 0) {
            dbgFPR = graph.getDbgbf().getFPR();
        }
        
        if (covFPR <= 0) {
            covFPR = graph.getCbf().getFPR();
        }        
                
        int shortestFragmentLengthAllowed = k;
        int leftReadLengthThreshold = k;
        int rightReadLengthThreshold = k;
        
        boolean assemblePolyaTails = this.minPolyATailLengthRequired > 0;
        
        FastxPairSequenceIterator rin = new FastxPairSequenceIterator(fastxPairs, seqPattern, qualPatternFrag);
        
        ArrayBlockingQueue<Fragment> fragments = new ArrayBlockingQueue<>(sampleSize);
                
        FragmentAssembler[] workers = new FragmentAssembler[numThreads];
        Thread[] threads = new Thread[numThreads];
        for (int i=0; i<numThreads; ++i) {
            workers[i] = new FragmentAssembler(rin,
                                                fragments,
                                                bound,
                                                minOverlap,
                                                false, // do not store paired kmers
                                                maxErrCorrIterations, 
                                                leftReadLengthThreshold,
                                                rightReadLengthThreshold,
                                                extendFragments,
                                                minKmerCov,
                                                keepArtifact
                                               );
            threads[i] = new Thread(workers[i]);
            threads[i].start();
        }
        
        while (true) {
            if (fragments.remainingCapacity() == 0) {
                break;
            }
            
            int numDone = 0;
            for (FragmentAssembler w : workers) {
                if (w.done) {
                    ++numDone;
                }
            }
            
            if (numDone == workers.length) {
                break;
            }
            
            Thread.sleep(10);
        }
        
        // Calculate length stats
        ArrayList<Integer> fragLengths = new ArrayList<>(sampleSize);
        for (Fragment frag : fragments) {
            if (!frag.isUnconnectedRead) {
                fragLengths.add(frag.length);
            }
        }

        if (fragLengths.isEmpty()) {
            // use lengths of unconnected reads as fragment lengths
            for (Fragment frag : fragments) {
                fragLengths.add(frag.left.length());
                fragLengths.add(frag.right.length());
            }
        }
        
        int[] fragLengthsStats = getMinQ1MedianQ3Max(fragLengths);
        
        longFragmentLengthThreshold = fragLengthsStats[1];
        
        assert longFragmentLengthThreshold - k - minNumKmerPairs > 0; // otherwise, no kmer pairs can be extracted
        
        setPairedKmerDistance(longFragmentLengthThreshold);
        
        int newBound = getPairedReadsMaxDistance(fragLengthsStats);
        for (FragmentAssembler w : workers) {
            w.updateBound(newBound);
        }
        
        System.out.println("Fragment Lengths Sampling Distribution (n=" + fragLengths.size() + ")");
        System.out.println("\tmin\tQ1\tM\tQ3\tmax");
        System.out.println("\t" + fragLengthsStats[0] + "\t" + fragLengthsStats[1] + "\t" + fragLengthsStats[2] + "\t" + fragLengthsStats[3] + "\t" + fragLengthsStats[4]);
        System.out.println("Paired kmers distance:       " + (longFragmentLengthThreshold - k - minNumKmerPairs));
        System.out.println("Max graph traversal depth:   " + newBound);

        FragmentWriterWorker writer = new FragmentWriterWorker(fragments, fragPaths, assemblePolyaTails, shortestFragmentLengthAllowed);
        Thread writerThread = new Thread(writer);
        writerThread.start();
        
        for (Thread t : threads) {
            t.join();
        }

        writer.stopWhenEmpty();
        writerThread.join();
        
        long numParsed = 0;
        for (FragmentAssembler w : workers) {
            numParsed += w.numParsed;
        }
        
        long numConnected = writer.getNumConnected();
        long numUnconnected = writer.getNumUnconnected();
        long numDiscarded = numParsed - numConnected - numUnconnected;

        System.out.println("Parsed " + NumberFormat.getInstance().format(numParsed) + " read pairs.");
        System.out.println("\tconnected:\t" + NumberFormat.getInstance().format(numConnected) + "\t(" + numConnected*100f/numParsed + "%)");
        System.out.println("\tnot connected:\t" + NumberFormat.getInstance().format(numUnconnected) + "\t(" + numUnconnected*100f/numParsed + "%)");
        System.out.println("\tdiscarded:\t" + NumberFormat.getInstance().format(numDiscarded) + "\t(" + numDiscarded*100f/numParsed + "%)");
        System.out.println("Fragments paired kmers Bloom filter FPR: " + graph.getPkbfFPR() * 100   + " %");
        System.out.println("Screening Bloom filter FPR:              " + screeningBf.getFPR() * 100 + " %");

        return fragLengthsStats;
    }

    public void updateGraphDesc(File graphFile) throws IOException {
        graph.saveDesc(graphFile);
    }
    
    public void savePairedKmersBloomFilter(File graphFile) throws IOException {
        graph.savePkbf(graphFile);
    }
    
    public void restorePairedKmersBloomFilter(File graphFile) throws IOException {
        graph.destroyFpkbf();
        graph.restorePkbf(graphFile);
        graph.updateFragmentKmerDistance(graphFile);
    }
    
    private long assembleTranscriptsMultiThreadedHelper(String fragmentsPath, 
                                                    TranscriptWriter writer, 
                                                    int sampleSize, 
                                                    int numThreads, 
                                                    boolean includeNaiveExtensions,
                                                    boolean extendBranchFreeFragmentsOnly,
                                                    boolean keepArtifact,
                                                    boolean keepChimera,
                                                    boolean reqFragKmersConsistency,
                                                    float minKmerCov) throws InterruptedException, IOException {
        
        NucleotideBitsReader fin = new NucleotideBitsReader(fragmentsPath);
        
        TranscriptAssemblyWorker[] workers = new TranscriptAssemblyWorker[numThreads];
        Thread[] threads = new Thread[numThreads];
        for (int i=0; i<numThreads; ++i) {
            workers[i] = new TranscriptAssemblyWorker(fin, writer, includeNaiveExtensions, extendBranchFreeFragmentsOnly, keepArtifact, keepChimera, reqFragKmersConsistency, minKmerCov);
            threads[i] = new Thread(workers[i]);
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join();
        }
        
        fin.close();
        
        long numFragmentsParsed = 0;
        for (TranscriptAssemblyWorker w : workers) {
            numFragmentsParsed += w.numParsed;
        }
        
        return numFragmentsParsed;
    }

    private class SingleEndReadsIterator implements SequenceFileIteratorInterface {
        private FastaFilteredSequenceIterator faItr = null;
        private FastqFilteredSequenceIterator fqItr = null;
        private long numReadsParsed = 0;
        
        public SingleEndReadsIterator(String[] readPaths, boolean reverseComplement) throws IOException {
            ArrayList<String> fastaPaths = new ArrayList<>();
            ArrayList<String> fastqPaths = new ArrayList<>();
            for (String p : readPaths) {
                if (FastaReader.isCorrectFormat(p)) {
                    fastaPaths.add(p);
                }
                else if (FastqReader.isCorrectFormat(p)) {
                    fastqPaths.add(p);
                }
            }
            
            if (!fastaPaths.isEmpty()) {
                String[] paths = new String[fastaPaths.size()];
                fastaPaths.toArray(paths);
                faItr = new FastaFilteredSequenceIterator(paths, seqPattern, reverseComplement);
            }
            
            if (!fastqPaths.isEmpty()) {
                String[] paths = new String[fastqPaths.size()];
                fastqPaths.toArray(paths);
                fqItr = new FastqFilteredSequenceIterator(paths, seqPattern, qualPatternFrag, reverseComplement);
            }
        }

        @Override
        public synchronized String next() throws IOException {
            if (faItr != null && faItr.hasNext()) {
                ++numReadsParsed;
                ArrayList<String> segments = faItr.nextSegments();
                while (segments != null) {
                    if (!segments.isEmpty()) {
                        String seq = connect(segments, graph, lookahead);
                        if (seq.length() >= k) {
                            return seq;
                        }
                    }
                    ++numReadsParsed;
                    segments = faItr.nextSegments();
                }
            }
            
            if (fqItr != null && fqItr.hasNext()) {
                ++numReadsParsed;
                ArrayList<String> segments = fqItr.nextSegments();
                while (segments != null) {
                    if (!segments.isEmpty()) {
                        String seq = connect(segments, graph, lookahead);
                        if (seq.length() >= k) {
                            return seq;
                        }
                    }
                    ++numReadsParsed;
                    segments = fqItr.nextSegments();
                }
            }
            
            return null;
        }
    }
    
    public void assembleSingleEndReads(String[] readPaths,
                                    boolean reverseComplement,
                                    String outFasta,
                                    String outFastaShort,
                                    int numThreads,
                                    int minTranscriptLength,
                                    boolean keepArtifact,
                                    boolean keepChimera,
                                    String txptNamePrefix,
                                    float minKmerCov,
                                    boolean writeUracil) throws IOException, InterruptedException {

        //boolean assemblePolya = minPolyATailLengthRequired > 0;
        /*@TODO support prioritized assembly of polya reads */
                
        boolean includeNaiveExtensions = true;
        boolean extendBranchFreeFragmentsOnly = false;

        FastaWriter fout = new FastaWriter(outFasta, false);
        FastaWriter foutShort = new FastaWriter(outFastaShort, false);
        TranscriptWriter writer = new TranscriptWriter(fout, foutShort, minTranscriptLength, maxTipLength, writeUracil);
        
        SingleEndReadsIterator readsItr = new SingleEndReadsIterator(readPaths, reverseComplement);
        
        TranscriptAssemblyWorker[] workers = new TranscriptAssemblyWorker[numThreads];
        Thread[] threads = new Thread[numThreads];
        for (int i=0; i<numThreads; ++i) {
            workers[i] = new TranscriptAssemblyWorker(readsItr, writer, includeNaiveExtensions, extendBranchFreeFragmentsOnly, keepArtifact, keepChimera, false, minKmerCov);
            threads[i] = new Thread(workers[i]);
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        fout.close();
        foutShort.close();
        
        System.out.println("Parsed " + NumberFormat.getInstance().format(readsItr.numReadsParsed) + " reads.");
    }
    
    public void assembleTranscriptsMultiThreaded(FragmentPaths fragPaths,
                                                String outFasta,
                                                String outFastaShort,
                                                String graphFile,
                                                int numThreads,
                                                int sampleSize,
                                                int minTranscriptLength,
                                                boolean keepArtifact,
                                                boolean keepChimera,
                                                boolean reqFragKmersConsistency,
                                                String txptNamePrefix,
                                                float minKmerCov,
                                                String branchFreeExtensionThreshold,
                                                boolean writeUracil) throws IOException, InterruptedException {
        
        long numFragmentsParsed = 0;

        boolean assemblePolya = minPolyATailLengthRequired > 0;

        FastaWriter fout = new FastaWriter(outFasta, false);
        FastaWriter foutShort = new FastaWriter(outFastaShort, false);
        //TranscriptWriter writer = new TranscriptWriter(fout, foutShort, minTranscriptLength, sensitiveMode ? maxTipLength : Math.max(k, maxTipLength));
        TranscriptWriter writer = new TranscriptWriter(fout, foutShort, minTranscriptLength, maxTipLength, writeUracil);


        boolean allowNaiveExtension = true;
        boolean extendBranchFreeOnly;

        if (assemblePolya) {
            // extend LONG fragments
            for (int mag=fragPaths.longPolyaFragmentsPaths.length-1; mag>=0; --mag) {
                writer.setOutputPrefix(txptNamePrefix + "E" + mag + ".L.");
                String fragmentsFasta = fragPaths.longPolyaFragmentsPaths[mag];
                System.out.println("Parsing `" + fragmentsFasta + "`...");
                extendBranchFreeOnly = isLowerStratum(COVERAGE_ORDER[mag], branchFreeExtensionThreshold);
                numFragmentsParsed += assembleTranscriptsMultiThreadedHelper(fragmentsFasta, writer, sampleSize, numThreads,
                                                                        allowNaiveExtension, extendBranchFreeOnly, 
                                                                        keepArtifact, keepChimera, reqFragKmersConsistency, minKmerCov);
            }

            // extend SHORT fragments
            for (int mag=fragPaths.shortPolyaFragmentsPaths.length-1; mag>=0; --mag) {
                writer.setOutputPrefix(txptNamePrefix + "E" + mag + ".S.");
                String fragmentsFasta = fragPaths.shortPolyaFragmentsPaths[mag];
                System.out.println("Parsing `" + fragmentsFasta + "`...");
                extendBranchFreeOnly = isLowerStratum(COVERAGE_ORDER[mag], branchFreeExtensionThreshold);
                numFragmentsParsed += assembleTranscriptsMultiThreadedHelper(fragmentsFasta, writer, sampleSize, numThreads,
                                                                        allowNaiveExtension, extendBranchFreeOnly,
                                                                        keepArtifact, keepChimera, reqFragKmersConsistency, minKmerCov);
            }

            // extend UNCONNECTED reads
            for (int mag=fragPaths.unconnectedPolyaReadsPaths.length-1; mag>=0; --mag) {
                writer.setOutputPrefix(txptNamePrefix + "E" + mag + ".U.");
                String fragmentsFasta = fragPaths.unconnectedPolyaReadsPaths[mag];
                System.out.println("Parsing `" + fragmentsFasta + "`...");
                extendBranchFreeOnly = isLowerStratum(COVERAGE_ORDER[mag], branchFreeExtensionThreshold);
                numFragmentsParsed += assembleTranscriptsMultiThreadedHelper(fragmentsFasta, writer, sampleSize, numThreads,
                                                                        allowNaiveExtension, extendBranchFreeOnly,
                                                                        keepArtifact, keepChimera, reqFragKmersConsistency, minKmerCov);
            }

            extendBranchFreeOnly = isLowerStratum(STRATUM_01, branchFreeExtensionThreshold);

            // extend LONG singleton fragments
            writer.setOutputPrefix(txptNamePrefix + "01.L.");
            System.out.println("Parsing `" + fragPaths.longPolyaSingletonsPath + "`...");
            numFragmentsParsed += assembleTranscriptsMultiThreadedHelper(fragPaths.longPolyaSingletonsPath, writer, sampleSize, numThreads,
                                                                    allowNaiveExtension, extendBranchFreeOnly,
                                                                    keepArtifact, keepChimera, reqFragKmersConsistency, minKmerCov);

            // extend SHORT singleton fragments
            writer.setOutputPrefix(txptNamePrefix + "01.S.");
            System.out.println("Parsing `" + fragPaths.shortPolyaSingletonsPath + "`...");
            numFragmentsParsed += assembleTranscriptsMultiThreadedHelper(fragPaths.shortPolyaSingletonsPath, writer, sampleSize, numThreads,
                                                                    allowNaiveExtension, extendBranchFreeOnly,
                                                                    keepArtifact, keepChimera, reqFragKmersConsistency, minKmerCov);

            // extend UNCONNECTED reads
            writer.setOutputPrefix(txptNamePrefix + "01.U.");
            System.out.println("Parsing `" + fragPaths.unconnectedPolyaSingletonsPath + "`...");
            numFragmentsParsed += assembleTranscriptsMultiThreadedHelper(fragPaths.unconnectedPolyaSingletonsPath, writer, sampleSize, numThreads,
                                                                    allowNaiveExtension, extendBranchFreeOnly,
                                                                    keepArtifact, keepChimera, reqFragKmersConsistency, minKmerCov);
        }


        // extend LONG fragments
        for (int mag=fragPaths.longFragmentsPaths.length-1; mag>=0; --mag) {
            writer.setOutputPrefix(txptNamePrefix + "E" + mag + ".L.");
            String fragmentsFasta = fragPaths.longFragmentsPaths[mag];
            System.out.println("Parsing `" + fragmentsFasta + "`...");
            extendBranchFreeOnly = isLowerStratum(COVERAGE_ORDER[mag], branchFreeExtensionThreshold);
            numFragmentsParsed += assembleTranscriptsMultiThreadedHelper(fragmentsFasta, writer, sampleSize, numThreads,
                                                                    allowNaiveExtension, extendBranchFreeOnly, 
                                                                    keepArtifact, keepChimera, reqFragKmersConsistency, minKmerCov);
        }          

        // extend SHORT fragments
        for (int mag=fragPaths.shortFragmentsPaths.length-1; mag>=0; --mag) {
            writer.setOutputPrefix(txptNamePrefix + "E" + mag + ".S.");
            String fragmentsFasta = fragPaths.shortFragmentsPaths[mag];
            System.out.println("Parsing `" + fragmentsFasta + "`...");
            extendBranchFreeOnly = isLowerStratum(COVERAGE_ORDER[mag], branchFreeExtensionThreshold);
            numFragmentsParsed += assembleTranscriptsMultiThreadedHelper(fragmentsFasta, writer, sampleSize, numThreads,
                                                                    allowNaiveExtension, extendBranchFreeOnly,
                                                                    keepArtifact, keepChimera, reqFragKmersConsistency, minKmerCov);
        }

        // extend UNCONNECTED reads
        for (int mag=fragPaths.unconnectedReadsPaths.length-1; mag>=0; --mag) {
            writer.setOutputPrefix(txptNamePrefix + "E" + mag + ".U.");
            String fragmentsFasta = fragPaths.unconnectedReadsPaths[mag];
            System.out.println("Parsing `" + fragmentsFasta + "`...");
            extendBranchFreeOnly = isLowerStratum(COVERAGE_ORDER[mag], branchFreeExtensionThreshold);
            numFragmentsParsed += assembleTranscriptsMultiThreadedHelper(fragmentsFasta, writer, sampleSize, numThreads,
                                                                    allowNaiveExtension, extendBranchFreeOnly,
                                                                    keepArtifact, keepChimera, reqFragKmersConsistency, minKmerCov);
        }

        extendBranchFreeOnly = isLowerStratum(STRATUM_01, branchFreeExtensionThreshold);

        // extend LONG singleton fragments
        writer.setOutputPrefix(txptNamePrefix + "01.L.");
        System.out.println("Parsing `" + fragPaths.longSingletonsPath + "`...");
        numFragmentsParsed += assembleTranscriptsMultiThreadedHelper(fragPaths.longSingletonsPath, writer, sampleSize, numThreads,
                                                                allowNaiveExtension, extendBranchFreeOnly,
                                                                keepArtifact, keepChimera, reqFragKmersConsistency, minKmerCov);

        // extend SHORT singleton fragments
        writer.setOutputPrefix(txptNamePrefix + "01.S.");
        System.out.println("Parsing `" + fragPaths.shortSingletonsPath + "`...");
        numFragmentsParsed += assembleTranscriptsMultiThreadedHelper(fragPaths.shortSingletonsPath, writer, sampleSize, numThreads,
                                                                allowNaiveExtension, extendBranchFreeOnly,
                                                                keepArtifact, keepChimera, reqFragKmersConsistency, minKmerCov);

        // extend UNCONNECTED reads
        writer.setOutputPrefix(txptNamePrefix + "01.U.");
        System.out.println("Parsing `" + fragPaths.unconnectedSingletonsPath + "`...");
        numFragmentsParsed += assembleTranscriptsMultiThreadedHelper(fragPaths.unconnectedSingletonsPath, writer, sampleSize, numThreads,
                                                                allowNaiveExtension, extendBranchFreeOnly,
                                                                keepArtifact, keepChimera, reqFragKmersConsistency, minKmerCov);

        fout.close();
        foutShort.close();

        System.out.println("Parsed " + NumberFormat.getInstance().format(numFragmentsParsed) + " fragments.");
        System.out.println("Screening Bloom filter FPR:      " + screeningBf.getFPR() * 100 + " %");
    }
            
    private static class MyTimer {
        private final long globalStartTime;
        private long startTime;
        
        public MyTimer() {
            globalStartTime = System.currentTimeMillis();
            startTime = globalStartTime;
        }
        
        public void start() {
            startTime = System.currentTimeMillis();
        }
        
        public long elapsedMillis() {
            return System.currentTimeMillis() - startTime;
        }
                
        public long totalElapsedMillis() {
            return System.currentTimeMillis() - globalStartTime;
        }
        
        public static String hmsFormat(long millis) {
            long seconds = millis / 1000;
            
            long hours = seconds / 3600;
            
            seconds = seconds % 3600;
            
            long minutes = seconds / 60;
            
            seconds = seconds % 60;
            
            StringBuilder sb = new StringBuilder();
            
            if (hours > 0) {
                sb.append(hours);
                sb.append("h ");
            }
            
            if (minutes > 0 || hours > 0) {
                sb.append(minutes);
                sb.append("m ");
            }
                        
            if (hours == 0 && minutes == 0) {
                sb.append(millis/1000f);
                sb.append("s");
            }
            else {
                sb.append(seconds);
                sb.append("s");
            }
            
            return sb.toString();
        }
    }
    
    public static void touch(File f) throws IOException {
        f.getParentFile().mkdirs();
        if (!f.createNewFile()){
            f.setLastModified(System.currentTimeMillis());
        }
    }
    
    public static void saveStringToFile(String path, String str) throws IOException {
        FileWriter writer = new FileWriter(path, false);
        writer.write(str);
        writer.close();
    }
    
    public static String loadStringFromFile(String path) throws FileNotFoundException, IOException {
        BufferedReader br = new BufferedReader(new FileReader(path));
        String line = br.readLine().strip();
        return line;
    }
    
    public static void printHelp(Options options, boolean error) {
        printVersionInfo(false);
        System.out.println();
        
        HelpFormatter formatter = new HelpFormatter();
        formatter.setOptionComparator(null);
        formatter.printHelp( "java -jar RNA-Bloom.jar", options, true);
        
        if (error) {
            System.exit(1);
        }
        else {
            System.exit(0);
        }
    }
    
    public static void printVersionInfo(boolean exit) {
        System.out.println(
                "RNA-Bloom v" + VERSION + "\n" +
                "Ka Ming Nip, Canada's Michael Smith Genome Sciences Centre, BC Cancer\n" +
                "Copyright 2018-present"
        );
        
        if (exit) {
            System.exit(0);
        }
    }

    private final static String FIELD_SEPARATOR = "\\s+"; // any white space character
    
    private static boolean getPooledReadPaths(String pooledReadPathsListFile,
            HashMap<String, ArrayList<String>> pooledLeftReadPaths,
            HashMap<String, ArrayList<String>> pooledRightReadPaths) throws FileNotFoundException, IOException {
        
        BufferedReader br = new BufferedReader(new FileReader(pooledReadPathsListFile));
        
        String line;
        int lineNumber = 0;
        while ((line = br.readLine()) != null) {
            ++lineNumber;
            line = line.trim();
            
            if (line.isEmpty()) {
                continue;
            }
            
            String[] entry = line.split(FIELD_SEPARATOR);
            
            //SAMPLE_ID LEFT_PATH RIGHT_PATH
            if (entry.length == 3) {
                String id = entry[0];

                ArrayList<String> paths = pooledLeftReadPaths.get(id);
                if (paths == null) {
                    paths = new ArrayList<>();
                    pooledLeftReadPaths.put(id, paths);
                }
                paths.add(entry[1]);

                paths = pooledRightReadPaths.get(id);
                if (paths == null) {
                    paths = new ArrayList<>();
                    pooledRightReadPaths.put(id, paths);
                }
                paths.add(entry[2]);
            }
            else {
                exitOnError("Pool reads path file has unexpected number of columns on line " + lineNumber + ":\n\t" + line);
                return false;
            }
        }
        
        br.close();
        return true;
    }
    
    /*
    private static long correctLongReads(RNABloom assembler, 
            String[] readFastxPaths, String[][] correctedLongReadFileNames, String repeatReadsFileName,
            int maxErrCorrItr, int minKmerCov, int numThreads, int sampleSize, int minSeqLen, boolean reverseComplement, boolean trimArtifact) throws InterruptedException, IOException, Exception {
        
        // set up the file writers
        final int numCovStrata = COVERAGE_ORDER.length;
        final int numLenStrata = LENGTH_STRATUM_NAMES.length;
        FastaWriter[][] writers = new FastaWriter[numCovStrata][numLenStrata];
        for (int c=0; c<numCovStrata; ++c) {
            for (int l=0; l<numLenStrata; ++l) {
                writers[c][l] = new FastaWriter(correctedLongReadFileNames[c][l], true);
            }
        }
        
        FastaWriter repeatReadsWriter = new FastaWriter(repeatReadsFileName, true);
        
        long numCorrectedReads = assembler.correctLongReadsMultithreaded(readFastxPaths, writers, repeatReadsWriter, minKmerCov, maxErrCorrItr, numThreads, sampleSize, minSeqLen, reverseComplement, trimArtifact);
        
        for (int i=0; i<writers.length; ++i) {
            for (int j=0; j<writers[i].length; ++j) {
                writers[i][j].close();
            }
        }
        
        repeatReadsWriter.close();
        
        return numCorrectedReads;
    }
    */
    
    private static long correctLongReads(RNABloom assembler, 
            String[] inFastxList, String outLongFasta, String outShortFasta, String outRepeatsFasta,
            int maxErrCorrItr, int minKmerCov, int numThreads, int sampleSize, int minSeqLen, 
            boolean reverseComplement, boolean trimArtifact, boolean writeUracil) throws InterruptedException, IOException, Exception {
        
        FastaWriter longWriter = new FastaWriter(outLongFasta, false);
        FastaWriter shortWriter = new FastaWriter(outShortFasta, false);
        FastaWriter repeatsWriter = new FastaWriter(outRepeatsFasta, false);

        long numCorrected = assembler.correctLongReadsMultithreaded(inFastxList,
                                                longWriter, shortWriter, repeatsWriter,
                                                minKmerCov,
                                                maxErrCorrItr,
                                                numThreads,
                                                sampleSize,
                                                minSeqLen,
                                                reverseComplement,
                                                trimArtifact,
                                                writeUracil);
        
        longWriter.close();
        shortWriter.close();
        repeatsWriter.close();
        
        return numCorrected;
    }
    
    /*
    private static void clusterLongReads(RNABloom assembler, 
            String[][] correctedLongReadFileNames, String clusteredLongReadsDirectory,
            int sketchSize, int numThreads, boolean useCompressedMinimizers,
            int minimizerSize, int minimizerWindowSize, float minSketchOverlapPercentage, int minSketchOverlapNumber) throws IOException, InterruptedException {
        
        File outdir = new File(clusteredLongReadsDirectory);
        if (outdir.exists()) {
            for (File f : outdir.listFiles()) {
                f.delete();
            }
        }
        else {
            outdir.mkdirs();
        }
        
        assembler.destroyAllBf();
        
        assembler.clusterLongReads(correctedLongReadFileNames, clusteredLongReadsDirectory, sketchSize, numThreads, useCompressedMinimizers,
                minimizerSize, minimizerWindowSize, minSketchOverlapPercentage, minSketchOverlapNumber);
    }
    
    private static boolean assembleLongReads(RNABloom assembler, 
            String clusteredLongReadsDirectory, String assembledLongReadsDirectory,
            String assembledLongReadsCombined,
            int numThreads, boolean forceOverwrite,
            boolean writeUracil, String minimapOptions, int minKmerCov, String txptNamePrefix, 
            boolean stranded, int minTranscriptLength, boolean removeArtifacts, boolean usePacBioPreset) throws IOException {
        
        File outdir = new File(assembledLongReadsDirectory);
        if (outdir.exists()) {
            if (forceOverwrite) {
                for (File f : outdir.listFiles()) {
                    f.delete();
                }
            }
        }
        else {
            outdir.mkdirs();
        }
        
        return assembler.assembleLongReads(clusteredLongReadsDirectory, assembledLongReadsDirectory, assembledLongReadsCombined,
                numThreads, writeUracil, minimapOptions, minKmerCov, txptNamePrefix, stranded, minTranscriptLength, removeArtifacts, usePacBioPreset);
    }
    
    private static boolean assembleUnclusteredLongReads(RNABloom assembler,
            String readsFasta, String outFasta, String tmpPrefix, 
            int numThreads, boolean forceOverwrite,
            String minimapOptions, int minKmerCov,
            int maxEdgeClip, float minAlnId, int minOverlapMatches,
            String txptNamePrefix, boolean stranded, boolean removeArtifacts,
            int minSeqDepth, boolean usePacBioPreset) throws IOException {
        
        if (forceOverwrite) {
            Files.deleteIfExists(FileSystems.getDefault().getPath(outFasta));
        }
        
        return assembler.assembleUnclusteredLongReads(readsFasta, 
                                    tmpPrefix, 
                                    outFasta,
                                    numThreads,
                                    minimapOptions,
                                    minKmerCov,
                                    maxEdgeClip,
                                    minAlnId,
                                    minOverlapMatches,
                                    txptNamePrefix,
                                    stranded,
                                    removeArtifacts,
                                    minSeqDepth,
                                    usePacBioPreset);
    }
    */
    
    private static boolean assembleClusteredLongReads(RNABloom assembler,
            String readsFasta, String clusterdir, String outFasta,
            boolean writeUracil, int numThreads, boolean forceOverwrite,
            String minimapOptions, int minKmerCov,
            int maxEdgeClip, float minAlnId, int minOverlapMatches,
            String txptNamePrefix, boolean stranded, boolean removeArtifacts,
            int minSeqDepth, boolean usePacBioPreset, int maxMergedClusterSize) throws IOException {
        
        File outdir = new File(clusterdir);
        
        if (forceOverwrite) {
            Files.deleteIfExists(FileSystems.getDefault().getPath(outFasta));
            if (outdir.exists()) {
                for (File f : outdir.listFiles()) {
                    f.delete();
                }
            }
        }
        
        outdir.mkdirs();
        
        return assembler.assembleClusteredLongReads(readsFasta, 
                                    clusterdir, 
                                    outFasta,
                                    writeUracil,
                                    numThreads,
                                    minimapOptions,
                                    minKmerCov,
                                    maxEdgeClip,
                                    minAlnId,
                                    minOverlapMatches,
                                    txptNamePrefix,
                                    stranded,
                                    removeArtifacts,
                                    minSeqDepth,
                                    usePacBioPreset,
                                    maxMergedClusterSize,
                                    forceOverwrite);
    }
    
    private static void assembleFragments(RNABloom assembler, boolean forceOverwrite,
            String outdir, String name, FastxFilePair[] fqPairs,
            long sbfSize, long pkbfSize, int sbfNumHash, int pkbfNumHash, int numThreads,
            int bound, int minOverlap, int sampleSize, int maxErrCorrItr, boolean extendFragments,
            int minKmerCoverage, boolean keepArtifact) throws FileFormatException, IOException, InterruptedException {

        final File fragsDoneStamp = new File(outdir + File.separator + STAMP_FRAGMENTS_DONE);
        
        if (forceOverwrite || !fragsDoneStamp.exists()) {
            FragmentPaths fragPaths = new FragmentPaths(outdir, name);
            fragPaths.deleteAll();

            assembler.setupKmerScreeningBloomFilter(sbfSize, sbfNumHash);
            assembler.setupFragmentPairedKmersBloomFilter(pkbfSize, pkbfNumHash);

            int[] fragStats = assembler.assembleFragmentsMultiThreaded(fqPairs, 
                                                                        fragPaths,
                                                                        bound, 
                                                                        minOverlap,
                                                                        sampleSize,
                                                                        numThreads,
                                                                        maxErrCorrItr,
                                                                        extendFragments,
                                                                        minKmerCoverage,
                                                                        keepArtifact);

            String fragStatsFile = outdir + File.separator + name + ".fragstats";
            String graphFile = outdir + File.separator + name + ".graph";
            
            assembler.updateGraphDesc(new File(graphFile));
            assembler.writeFragStatsToFile(fragStats, fragStatsFile);

            touch(fragsDoneStamp);
        }
        else {
            System.out.println("WARNING: Fragments were already assembled for \"" + name + "!");
        }
    }
    
    private static void splitFastaByLength(String inFasta, String outLongFasta, String outShortFasta, int lengthThreshold) throws IOException {
        FastaReader fin = new FastaReader(inFasta);
        FastaWriter foutLong = new FastaWriter(outLongFasta, false);
        FastaWriter foutShort = new FastaWriter(outShortFasta, false);
        while(fin.hasNext()) {
            String[] nameCommentSeq = fin.nextWithComment();
            
            String header = nameCommentSeq[0];
            if (!nameCommentSeq[1].isEmpty()) {
                header += " " + nameCommentSeq[1];
            }
            
            String seq = nameCommentSeq[2];
            
            if (seq.length() >= lengthThreshold) {
                foutLong.write(header, seq);
            }
            else {
                foutShort.write(header, seq);
            }
        }
        foutLong.close();
        foutShort.close();
        fin.close();
    }
    
    private static boolean mergePooledAssemblies(String outdir, String assemblyName, String[] sampleNames,
            String txptFileExt, String shortTxptFileExt, String txptNamePrefix,
            int k, int numThreads, boolean stranded, int maxIndelSize, int maxTipLength,
            float percentIdentity, boolean removeArtifacts, int txptLengthThreshold, boolean writeUracil,
            boolean usePacBioPreset) throws IOException {
        
        String concatenatedFasta = outdir + File.separator + assemblyName + ".all" + FASTA_EXT;
        String reducedFasta      = outdir + File.separator + assemblyName + ".all_nr" + FASTA_EXT;
        String tmpPrefix         = outdir + File.separator + assemblyName + ".tmp";
        String outLongFasta      = outdir + File.separator + assemblyName + ".transcripts" + FASTA_EXT;
        String outShortFasta     = outdir + File.separator + assemblyName + ".transcripts.short" + FASTA_EXT;

        // combine assembly files
        FastaWriter fout = new FastaWriter(concatenatedFasta, false);
        FastaReader fin;
        for (String sampleName : sampleNames) {
            String longTxptsPath  = outdir + File.separator + sampleName + File.separator + sampleName + ".transcripts" + txptFileExt;
            String shortTxptsPath = outdir + File.separator + sampleName + File.separator + sampleName + ".transcripts" + shortTxptFileExt;
            
            fin = new FastaReader(longTxptsPath);
            while(fin.hasNext()) {
                String[] nameCommentSeq = fin.nextWithComment();
                //String comment = nameCommentSeq[1];
                String seq = nameCommentSeq[2];

                if (writeUracil) {
                    seq = seq.replace('T', 'U');
                }

                fout.write(txptNamePrefix + sampleName + "_" + nameCommentSeq[0], seq);
            }
            fin.close();
            
            fin = new FastaReader(shortTxptsPath);
            while(fin.hasNext()) {
                String[] nameCommentSeq = fin.nextWithComment();
                //String comment = nameCommentSeq[1];
                String seq = nameCommentSeq[2];

                if (writeUracil) {
                    seq = seq.replace('T', 'U');
                }

                fout.write(txptNamePrefix + sampleName + "_" + nameCommentSeq[0], seq);
            }
            fin.close();
        }
        fout.close();

        boolean ok = overlapLayout(concatenatedFasta, tmpPrefix, reducedFasta, numThreads,
                        stranded, "-r " + Integer.toString(maxIndelSize), maxTipLength, percentIdentity, 2*k,
                        maxIndelSize, removeArtifacts, 1, usePacBioPreset);
        
        splitFastaByLength(reducedFasta, outLongFasta, outShortFasta, txptLengthThreshold);
        
        Files.deleteIfExists(FileSystems.getDefault().getPath(concatenatedFasta));
        Files.deleteIfExists(FileSystems.getDefault().getPath(reducedFasta));
        
        return ok;
    }
    
    private static void assembleTranscriptsNR(RNABloom assembler, String outdir, String name, boolean forceOverwrite,
            int numThreads, boolean keepArtifact, int minTranscriptLength, boolean usePacBioPreset) throws IOException {
        
        final File nrTxptsDoneStamp = new File(outdir + File.separator + STAMP_TRANSCRIPTS_NR_DONE);

        if (forceOverwrite || !nrTxptsDoneStamp.exists()) {
            String tmpPrefix               = outdir + File.separator + name + ".tmp";
            String transcriptsFasta        = outdir + File.separator + name + ".transcripts" + FASTA_EXT;
            String shortTranscriptsFasta   = outdir + File.separator + name + ".transcripts.short" + FASTA_EXT;
            String nrTranscriptsFasta      = outdir + File.separator + name + ".transcripts.nr" + FASTA_EXT;
            String shortNrTranscriptsFasta = outdir + File.separator + name + ".transcripts.nr.short" + FASTA_EXT;

            Files.deleteIfExists(FileSystems.getDefault().getPath(nrTranscriptsFasta));

            System.out.println("Reducing redundancy in assembled transcripts...");
            MyTimer timer = new MyTimer();
            timer.start();

            boolean ok = assembler.generateNonRedundantTranscripts(transcriptsFasta, shortTranscriptsFasta,
                    tmpPrefix, nrTranscriptsFasta, shortNrTranscriptsFasta, numThreads, !keepArtifact, minTranscriptLength,
                    usePacBioPreset);

            if (ok) {
                System.out.println("Redundancy reduced in " + MyTimer.hmsFormat(timer.elapsedMillis()));
                touch(nrTxptsDoneStamp);
            }
            else {
                exitOnError("Error during redundancy reduction!");
            }
        }
        else {
            System.out.println("WARNING: Redundancy reduction had completed previously for \"" + name + "\"!");
        } 
    }
    
    private static void assembleTranscriptsSE(RNABloom assembler, boolean forceOverwrite,
            String outdir, String name, String txptNamePrefix,
            long sbfSize, int sbfNumHash, int numThreads, 
            String[] readPaths, boolean reverseComplement,
            int minTranscriptLength, boolean keepArtifact, boolean keepChimera,
            float minKmerCov, boolean reduceRedundancy, boolean writeUracil,
            boolean usePacBioPreset) throws IOException, InterruptedException {

        final File txptsDoneStamp = new File(outdir + File.separator + STAMP_TRANSCRIPTS_DONE);
        final String transcriptsFasta      = outdir + File.separator + name + ".transcripts" + FASTA_EXT;
        final String shortTranscriptsFasta = outdir + File.separator + name + ".transcripts.short" + FASTA_EXT;
        
        if (forceOverwrite || !txptsDoneStamp.exists()) {
            MyTimer timer = new MyTimer();
                assembler.setupKmerScreeningBloomFilter(sbfSize, sbfNumHash);
                                
                assembler.assembleSingleEndReads(readPaths,
                                    reverseComplement,
                                    transcriptsFasta,
                                    shortTranscriptsFasta,
                                    numThreads,
                                    minTranscriptLength,
                                    keepArtifact,
                                    keepChimera,
                                    txptNamePrefix,
                                    minKmerCov,
                                    writeUracil);
            System.out.println("Transcripts assembled in " + MyTimer.hmsFormat(timer.elapsedMillis()));

            touch(txptsDoneStamp);

            System.out.println("Assembled transcripts at `" + transcriptsFasta + "`");
        }
        else {
            System.out.println("WARNING: Transcripts were already assembled for \"" + name + "\"!");
        }
        
        if (reduceRedundancy) {
            assembleTranscriptsNR(assembler, outdir, name, forceOverwrite, numThreads, keepArtifact, minTranscriptLength, usePacBioPreset);
        }
    }
    
    private static void assembleTranscriptsPE(RNABloom assembler, boolean forceOverwrite,
            String outdir, String name, String txptNamePrefix,
            long sbfSize, int sbfNumHash, long pkbfSize, int pkbfNumHash, int numThreads, boolean noFragDBG,
            int sampleSize, int minTranscriptLength, boolean keepArtifact, boolean keepChimera, 
            boolean reqFragKmersConsistency, boolean restorePairedKmers,
            float minKmerCov, String branchFreeExtensionThreshold,
            boolean reduceRedundancy, boolean assemblePolya, boolean writeUracil,
            String[] refTranscriptPaths, boolean usePacBioPreset) throws IOException, InterruptedException {
        
        final File txptsDoneStamp = new File(outdir + File.separator + STAMP_TRANSCRIPTS_DONE);
        final String transcriptsFasta = outdir + File.separator + name + ".transcripts" + FASTA_EXT;
        final String shortTranscriptsFasta = outdir + File.separator + name + ".transcripts.short" + FASTA_EXT;
                
        if (forceOverwrite || !txptsDoneStamp.exists()) {
            MyTimer timer = new MyTimer();

            FragmentPaths fragPaths = new FragmentPaths(outdir, name);
            
            final String graphFile = outdir + File.separator + name + ".graph";
            
            if (!noFragDBG) {
                if (assembler.isGraphInitialized()) {
                    assembler.clearDbgBf();
                    assembler.clearRpkBf();
                }
                
                System.out.println("Rebuilding graph from assembled fragments...");
                timer.start();
                if (restorePairedKmers) {
                    assembler.setupFragmentPairedKmersBloomFilter(pkbfSize, pkbfNumHash);
                    assembler.updateFragmentKmerDistance(graphFile);
                }
                                
                assembler.populateGraphFromFragments(fragPaths.asList(assemblePolya),
                        refTranscriptPaths == null ? new ArrayList<>() : Arrays.asList(refTranscriptPaths),
                        restorePairedKmers, numThreads);
                
                System.out.println("Graph rebuilt in " + MyTimer.hmsFormat(timer.elapsedMillis()));
            }

            
            Files.deleteIfExists(FileSystems.getDefault().getPath(transcriptsFasta));
            Files.deleteIfExists(FileSystems.getDefault().getPath(shortTranscriptsFasta));

            System.out.println("Assembling transcripts...");
            timer.start();

            assembler.setupKmerScreeningBloomFilter(sbfSize, sbfNumHash);

            assembler.assembleTranscriptsMultiThreaded(fragPaths,
                                                        transcriptsFasta, 
                                                        shortTranscriptsFasta,
                                                        graphFile,
                                                        numThreads,
                                                        sampleSize,
                                                        minTranscriptLength,
                                                        keepArtifact,
                                                        keepChimera,
                                                        reqFragKmersConsistency,
                                                        txptNamePrefix,
                                                        minKmerCov,
                                                        branchFreeExtensionThreshold,
                                                        writeUracil);
            
            System.out.println("Transcripts assembled in " + MyTimer.hmsFormat(timer.elapsedMillis()));

            touch(txptsDoneStamp);

            System.out.println("Assembled transcripts at `" + transcriptsFasta + "`");
        }
        else {
            System.out.println("WARNING: Transcripts were already assembled for \"" + name + "\"!");
        }
                
        if (reduceRedundancy) {
            assembleTranscriptsNR(assembler, outdir, name, forceOverwrite, numThreads, keepArtifact, minTranscriptLength, usePacBioPreset);
        }
    }
    
    private boolean generateNonRedundantTranscripts(String inLongFasta, String inShortFasta,
            String tmpPrefix, String outLongFasta, String outShortFasta,
            int numThreads, boolean removeArtifacts, int txptLengthThreshold,
            boolean usePacBioPreset) throws IOException {
        
        String concatenatedFasta = tmpPrefix + "_ava_cat" + FASTA_EXT;
        String reducedFasta = tmpPrefix + "_ava_cat_nr" + FASTA_EXT;

        // combine assembly files
        FastaWriter fout = new FastaWriter(concatenatedFasta, false);
        
        FastaReader fin = new FastaReader(inLongFasta);
        while(fin.hasNext()) {
            String[] nameCommentSeq = fin.nextWithComment();
            //String comment = nameCommentSeq[1];
            String seq = nameCommentSeq[2];
            fout.write(nameCommentSeq[0], seq);
        }
        fin.close();
        
        fin = new FastaReader(inShortFasta);
        while(fin.hasNext()) {
            String[] nameCommentSeq = fin.nextWithComment();
            //String comment = nameCommentSeq[1];
            String seq = nameCommentSeq[2];
            fout.write(nameCommentSeq[0], seq);
        }
        fin.close();
        
        fout.close();
        
        boolean ok = overlapLayout(concatenatedFasta, tmpPrefix, reducedFasta, 
                        numThreads, strandSpecific, "-r " + Integer.toString(maxIndelSize),
                        maxTipLength, percentIdentity, 2*k, maxIndelSize, removeArtifacts, 1, usePacBioPreset);
        
        splitFastaByLength(reducedFasta, outLongFasta, outShortFasta, txptLengthThreshold);
        
        Files.deleteIfExists(FileSystems.getDefault().getPath(concatenatedFasta));
        Files.deleteIfExists(FileSystems.getDefault().getPath(reducedFasta));
        
        return ok;
    }
    
    private static boolean hasNtcard() {
        try {
            String cmd = "ntcard --version";
            Runtime rt = Runtime.getRuntime();
            Process pr = rt.exec(cmd);
            int exitVal = pr.waitFor();
            return exitVal == 0;
        }
        catch (IOException | InterruptedException e) {
            return false;
        }
    }
    
    private static int[] getKmerSizes(String str) {
        /*  eg.
                25
                25,26,27
                25-27
                25-50:5
                25,26,27,30-55:5
        */

        HashSet<Integer> kSet = new HashSet<>();

        for (String kStr : str.split(",")) {
            String[] rangeStepStr = kStr.split(":", 2);

            int step = 1;
            if (rangeStepStr.length > 1) {
                step = Integer.parseInt(rangeStepStr[1].trim());
            }
            
            String[] rangeStr = rangeStepStr[0].split("-", 2);
            int start = Integer.parseInt(rangeStr[0].trim());
            kSet.add(start);
            
            if (rangeStr.length > 1) {
                int end = Integer.parseInt(rangeStr[1].trim());
                                
                for (int i=start+step; i<end; i+=step) {
                    kSet.add(i);
                }
                
                kSet.add(end);
            }
        }
        
        int[] kArr = new int[kSet.size()];
        int i = 0;
        for (int k : kSet) {
            kArr[i++] = k;
        }
        
        Arrays.sort(kArr);
        
        return kArr;
    }
    
    private static NTCardHistogram getNTCardHistogram(int threads, int k, String histogramPathPrefix, String readPathsFile, boolean forceOverwrite) throws IOException, InterruptedException {
        NTCardHistogram hist = null;
        String histogramPath = histogramPathPrefix + "_k" + k + ".hist";
        int exitVal = 0;
        
        if (forceOverwrite || !new File(histogramPath).isFile()) {
            String cmd = "ntcard -t " + threads + " -k " + k + " -c 65535 -p " + histogramPathPrefix + " @" + readPathsFile;
            Runtime rt = Runtime.getRuntime();
            System.out.println("Running command: `" + cmd + "`...");
            Process pr = rt.exec(cmd);
            exitVal = pr.waitFor();
        }
        
        if (exitVal == 0) {
            System.out.println("Parsing histogram file `" + histogramPath + "`...");
            hist = new NTCardHistogram(histogramPath);
            
        }
        
        return hist;
    }
    
    private static void printBloomFilterMemoryInfo(float dbgGB, float cbfGB, float pkbfGB, float sbfGB) {
        System.out.println(  "\nBloom filters          Memory (GB)");
        System.out.println(    "====================================");
        if (dbgGB > 0)
            System.out.println("de Bruijn graph:       " + dbgGB);
        if (cbfGB > 0)
            System.out.println("k-mer counting:        " + cbfGB);
        if (pkbfGB > 0) {
            System.out.println("paired k-mers (reads): " + pkbfGB);
            System.out.println("paired k-mers (frags): " + pkbfGB);
        }
        if (sbfGB > 0)
            System.out.println("screening:             " + sbfGB);
        System.out.println(    "====================================");
        System.out.println(    "Total:                 " + (dbgGB+cbfGB+2*pkbfGB+sbfGB));
    }
    
    private static boolean isListFile(String[] paths) {
        return paths.length == 1 && paths[0].charAt(0) == '@';
    }
    
    private static String[] getPathsFromListFile(String[] paths) throws IOException {
        return getNonEmptyLines(paths[0].substring(1));
    }
    
    private static String[] getNonEmptyLines(String textFile) throws IOException {
        ArrayList<String> lines = new ArrayList<>();
        
        BufferedReader br = new BufferedReader(new FileReader(textFile));
        
        for (String line; (line = br.readLine()) != null; ) {
            line = line.trim();
            
            if (line.isEmpty()) {
                continue;
            }
            
            lines.add(line);
        }
        
        String[] linesArray = new String[lines.size()];
        lines.toArray(linesArray);
        
        return linesArray;
    }
    
    private static final String STAMP_STARTED = "STARTED";
    private static final String STAMP_DBG_DONE = "DBG.DONE";
    private static final String STAMP_FRAGMENTS_DONE = "FRAGMENTS.DONE";
    private static final String STAMP_TRANSCRIPTS_DONE = "TRANSCRIPTS.DONE";
    private static final String STAMP_TRANSCRIPTS_NR_DONE = "TRANSCRIPTS_NR.DONE";
    private static final String STAMP_LONG_READS_CORRECTED = "LONGREADS.CORRECTED";
    private static final String STAMP_LONG_READS_CLUSTERED = "LONGREADS.CLUSTERED";
    private static final String STAMP_LONG_READS_ASSEMBLED = "LONGREADS.ASSEMBLED";
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {        
        MyTimer timer = new MyTimer();
                    
        // Based on: http://commons.apache.org/proper/commons-cli/usage.html
        CommandLineParser parser = new DefaultParser();

        Options options = new Options();

                
        Option optLeftReads = Option.builder("l")
                                    .longOpt("left")
                                    .desc("left reads file(s)")
                                    .hasArgs()
                                    .argName("FILE")
                                    .build();
        options.addOption(optLeftReads);
        
        Option optRightReads = Option.builder("r")
                                    .longOpt("right")
                                    .desc("right reads file(s)")
                                    .hasArgs()
                                    .argName("FILE")
                                    .build();
        options.addOption(optRightReads);
        
        Option optPooledAssembly = Option.builder("pool")
                                    .longOpt("pool")
                                    .desc("list of read files for pooled assembly")
                                    .hasArgs()
                                    .argName("FILE")
                                    .build();
        options.addOption(optPooledAssembly);
        
        String defaultMinTranscriptLengthLR = "200";
        String defaultMinOverlapLR = "150";
        String defaultKmerSizeLR = "17";
        String defaultMinCoverageLR = "2";
        String defaultMaxIndelSizeLR = "30";
        String defaultMaxTipLengthLR = "50";
        String defaultMaxErrorCorrItrLR = "2";
        String defaultPercentIdentityLR = "0.7";
        String defaultLongReadPreset = "-k " + defaultKmerSizeLR + " -c " + defaultMinCoverageLR + 
                " -indel " + defaultMaxIndelSizeLR + " -e " + defaultMaxErrorCorrItrLR +
                " -p " + defaultPercentIdentityLR + " -length " + defaultMinTranscriptLengthLR +
                " -overlap " + defaultMinOverlapLR + " -tip " + defaultMaxTipLengthLR;
        Option optLongReads = Option.builder("long")
                                    .desc("long reads file(s)\n(Requires `minimap2` and `racon` in PATH. Presets `" + 
                                            defaultLongReadPreset + "` unless each option is defined otherwise.)")
                                    .hasArgs()
                                    .argName("FILE")
                                    .build();
        options.addOption(optLongReads);

        Option optRefTranscripts = Option.builder("ref")
                                    .desc("reference transcripts file(s) for guiding the assembly process")
                                    .hasArgs()
                                    .argName("FILE")
                                    .build();
        options.addOption(optRefTranscripts);
        
        Option optRevCompLeft = Option.builder("rcl")
                                    .longOpt("revcomp-left")
                                    .desc("reverse-complement left reads [false]")
                                    .hasArg(false)
                                    .build();
        options.addOption(optRevCompLeft);

        Option optRevCompRight = Option.builder("rcr")
                                    .longOpt("revcomp-right")
                                    .desc("reverse-complement right reads [false]")
                                    .hasArg(false)
                                    .build();
        options.addOption(optRevCompRight);

        Option optRevCompLong = Option.builder("rc")
                                    .longOpt("revcomp-long")
                                    .desc("reverse-complement long reads [false]")
                                    .hasArg(false)
                                    .build();
        options.addOption(optRevCompLong);
        
        Option optStranded = Option.builder("ss")
                                    .longOpt("stranded")
                                    .desc("reads are strand specific [false]")
                                    .hasArg(false)
                                    .build();
        options.addOption(optStranded);
        
        final String optNameDefault = "rnabloom";
        Option optName = Option.builder("n")
                                    .longOpt("name")
                                    .desc("assembly name [" + optNameDefault + "]")
                                    .hasArg(true)
                                    .argName("STR")
                                    .build();
        options.addOption(optName);
        
        final String optPrefixDefault = "";
        Option optPrefix = Option.builder("prefix")
                                    .desc("name prefix in FASTA header for assembled transcripts")
                                    .hasArg(true)
                                    .argName("STR")
                                    .build();
        options.addOption(optPrefix);
        
        Option optUracil = Option.builder("u")
                                    .longOpt("uracil")
                                    .desc("output uracils (U) in place of thymines (T) in assembled transcripts [false]")
                                    .hasArg(false)
                                    .build();
        options.addOption(optUracil);
        
        final String optThreadsDefault = "2";
        Option optThreads = Option.builder("t")
                                    .longOpt("threads")
                                    .desc("number of threads to run [" + optThreadsDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optThreads);
        
        final String optOutdirDefault = System.getProperty("user.dir") + File.separator + "rnabloom_assembly";
        Option optOutdir = Option.builder("o")
                                    .longOpt("outdir")
                                    .desc("output directory [" + optOutdirDefault + "]")
                                    .hasArg(true)
                                    .argName("PATH")
                                    .build();
        options.addOption(optOutdir);
        
        Option optForce = Option.builder("f")
                                    .longOpt("force")
                                    .desc("force overwrite existing files [false]")
                                    .hasArg(false)
                                    .build();
        options.addOption(optForce);
        
        final String optKmerSizeDefault = "25"; 
        Option optKmerSize = Option.builder("k")
                                    .longOpt("kmer")
                                    .desc("k-mer size [" + optKmerSizeDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optKmerSize);
        
        final String optStageDefault = "3";
        Option optStage = Option.builder("stage")
                                    .desc("assembly termination stage\n" +
                                            "short reads: [3]\n" +
                                            "1. construct graph\n" +
                                            "2. assemble fragments\n" +
                                            "3. assemble transcripts\n" +
                                            "long reads: [3]\n" + 
                                            "1. construct graph\n" +
                                            "2. correct reads\n" +
                                            "3. assemble transcripts")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optStage);
        
        final String optBaseQualDbgDefault = "3";
        Option optBaseQualDbg = Option.builder("q")
                                    .longOpt("qual-dbg")
                                    .desc("minimum base quality in reads for constructing DBG [" + optBaseQualDbgDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optBaseQualDbg);

        final String optBaseQualFragDefault = "3";
        Option optBaseQualFrag = Option.builder("Q")
                                    .longOpt("qual-frag")
                                    .desc("minimum base quality in reads for fragment reconstruction [" + optBaseQualFragDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optBaseQualFrag);        
        
        final String optMinKmerCovDefault = "1"; 
        Option optMinKmerCov = Option.builder("c")
                                    .longOpt("mincov")
                                    .desc("minimum k-mer coverage [" + optMinKmerCovDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optMinKmerCov);
        
        final String optAllHashDefault = "2";
        Option optAllHash = Option.builder("hash")
                                    .desc("number of hash functions for all Bloom filters [" + optAllHashDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optAllHash); 
        
        Option optSbfHash = Option.builder("sh")
                                    .longOpt("sbf-hash")
                                    .desc("number of hash functions for screening Bloom filter [" + optAllHashDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optSbfHash); 
        
        Option optDbgbfHash = Option.builder("dh")
                                    .longOpt("dbgbf-hash")
                                    .desc("number of hash functions for de Bruijn graph Bloom filter [" + optAllHashDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optDbgbfHash);

        Option optCbfHash = Option.builder("ch")
                                    .longOpt("cbf-hash")
                                    .desc("number of hash functions for k-mer counting Bloom filter [" + optAllHashDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optCbfHash);
        
        Option optPkbfHash = Option.builder("ph")
                                    .longOpt("pkbf-hash")
                                    .desc("number of hash functions for paired k-mers Bloom filter [" + optAllHashDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optPkbfHash);        

        Option optNumKmers = Option.builder("nk")
                                    .longOpt("num-kmers")
                                    .desc("expected number of unique k-mers in input reads")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optNumKmers);
        
        Option optNtcard = Option.builder("ntcard")
                                    .desc("count unique k-mers in input reads with ntCard [false]\n(Requires `ntcard` in PATH. If this option is used along with `-long`, the value for `-c` is set automatically based on the ntCard histogram, unless `-c` is defined otherwise)")
                                    .hasArg(false)
                                    .build();
        options.addOption(optNtcard);        
        
        Option optAllMem = Option.builder("mem")
                                    .longOpt("memory")
                                    .desc("total amount of memory (GB) for all Bloom filters [auto]")
                                    .hasArg(true)
                                    .argName("DECIMAL")
                                    .build();
        options.addOption(optAllMem);
        
        Option optSbfMem = Option.builder("sm")
                                    .longOpt("sbf-mem")
                                    .desc("amount of memory (GB) for screening Bloom filter [auto]")
                                    .hasArg(true)
                                    .argName("DECIMAL")
                                    .build();
        options.addOption(optSbfMem);
        
        Option optDbgbfMem = Option.builder("dm")
                                    .longOpt("dbgbf-mem")
                                    .desc("amount of memory (GB) for de Bruijn graph Bloom filter [auto]")
                                    .hasArg(true)
                                    .argName("DECIMAL")
                                    .build();
        options.addOption(optDbgbfMem);

        Option optCbfMem = Option.builder("cm")
                                    .longOpt("cbf-mem")
                                    .desc("amount of memory (GB) for k-mer counting Bloom filter [auto]")
                                    .hasArg(true)
                                    .argName("DECIMAL")
                                    .build();
        options.addOption(optCbfMem);
        
        Option optPkbfMem = Option.builder("pm")
                                    .longOpt("pkbf-mem")
                                    .desc("amount of memory (GB) for paired kmers Bloom filter [auto]")
                                    .hasArg(true)
                                    .argName("DECIMAL")
                                    .build();
        options.addOption(optPkbfMem);

        final String optFprDefault = "0.01";
        Option optFpr = Option.builder("fpr")
                                    .longOpt("fpr")
                                    .desc("maximum allowable false-positive rate of Bloom filters [" + optFprDefault + "]")
                                    .hasArg(true)
                                    .argName("DECIMAL")
                                    .build();
        options.addOption(optFpr);
        
        Option optSaveBf = Option.builder("savebf")
                                    .desc("save graph (Bloom filters) from stage 1 to disk [false]")
                                    .hasArg(false)
                                    .build();
        options.addOption(optSaveBf);  
        
        final String optTipLengthDefault = "5";
        Option optTipLength = Option.builder("tiplength")
                                    .desc("maximum number of bases in a tip [" + optTipLengthDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optTipLength);
        
        final String optLookaheadDefault = "3";
        Option optLookahead = Option.builder("lookahead")
                                    .desc("number of k-mers to look ahead during graph traversal [" + optLookaheadDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optLookahead);        
        
        final String optSampleDefault = "1000";
        Option optSample = Option.builder("sample")
                                    .desc("sample size for estimating read/fragment lengths [" + optSampleDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optSample);
        
        final String optErrCorrItrDefault = "1";
        Option optErrCorrItr = Option.builder("e")
                                    .longOpt("errcorritr")
                                    .desc("number of iterations of error-correction in reads [" + optErrCorrItrDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optErrCorrItr);
        
        final String optMaxCovGradDefault = "0.50";
        Option optMaxCovGrad = Option.builder("grad")
                                    .longOpt("maxcovgrad")
                                    .desc("maximum k-mer coverage gradient for error correction [" + optMaxCovGradDefault + "]")
                                    .hasArg(true)
                                    .argName("DECIMAL")
                                    .build();
        options.addOption(optMaxCovGrad);
        
        final String optIndelSizeDefault = "1";
        Option optIndelSize = Option.builder("indel")
                                    .desc("maximum size of indels to be collapsed [" + optIndelSizeDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optIndelSize);  

        final String optPercentIdentityDefault = "0.90";
        Option optPercentIdentity = Option.builder("p")
                                    .longOpt("percent")
                                    .desc("minimum percent identity of sequences to be collapsed [" + optPercentIdentityDefault + "]")
                                    .hasArg(true)
                                    .argName("DECIMAL")
                                    .build();
        options.addOption(optPercentIdentity);
                
        final String optMinLengthDefault = "200";
        Option optMinLength = Option.builder("length")
                                    .desc("minimum transcript length in output assembly [" + optMinLengthDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optMinLength);  
        
        Option optNoReduce = Option.builder("norr")
                                    .desc("skip redundancy reduction for assembled transcripts [false]\n(will not create 'transcripts.nr.fa')")
                                    .hasArg(false)
                                    .build();
        options.addOption(optNoReduce);

        Option optMergePool = Option.builder("mergepool")
                                    .desc("merge pooled assemblies [false]\n(Requires `-pool`; overrides `-norr`)")
                                    .hasArg(false)
                                    .build();
        options.addOption(optMergePool);
        
        final String optOverlapDefault = "k-1";
        Option optOverlap = Option.builder("overlap")
                                    .desc("minimum number of overlapping bases between reads [" + optOverlapDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optOverlap);
        
        final String optBoundDefault = "500";
        Option optBound = Option.builder("bound")
                                    .desc("maximum distance between read mates [" + optBoundDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optBound);
        
        Option optExtend = Option.builder("extend")
                                    .desc("extend fragments outward during fragment reconstruction [false]")
                                    .hasArg(false)
                                    .build();
        options.addOption(optExtend);

        Option optNoFragmentsConsistency = Option.builder("nofc")
                                    .desc("turn off assembly consistency with fragment paired k-mers [false]")
                                    .hasArg(false)
                                    .build();
        options.addOption(optNoFragmentsConsistency);
        
        Option optSensitive = Option.builder("sensitive")
                                    .desc("assemble transcripts in sensitive mode [false]")
                                    .hasArg(false)
                                    .build();
        options.addOption(optSensitive);
        
        Option optKeepArtifact = Option.builder("artifact")
                                    .desc("keep potential sequencing artifacts [false]")
                                    .hasArg(false)
                                    .build();
        options.addOption(optKeepArtifact);

        Option optKeepChimera = Option.builder("chimera")
                                    .desc("keep potential chimeras [false]")
                                    .hasArg(false)
                                    .build();
        options.addOption(optKeepChimera);
                
        final String optBranchFreeExtensionDefault = STRATUM_E0;
        final String optBranchFreeExtensionChoicesStr = String.join("|", STRATA);
        Option optBranchFreeExtensionThreshold = Option.builder("stratum")
                                    .desc("fragments lower than the specified stratum are extended only if they are branch-free in the graph [" + optBranchFreeExtensionDefault + "]")
                                    .hasArg(true)
                                    .argName(optBranchFreeExtensionChoicesStr)
                                    .build();
        options.addOption(optBranchFreeExtensionThreshold);        
        
        final String optMinKmerPairsDefault = "10";
        Option optMinKmerPairs = Option.builder("pair")
                                    .desc("minimum number of consecutive k-mer pairs for assembling transcripts [" + optMinKmerPairsDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optMinKmerPairs);  
                
        final String optPolyATailDefault = "0";
        Option optPolyATail = Option.builder("a")
                                    .longOpt("polya")
                                    .desc("prioritize assembly of transcripts with poly-A tails of the minimum length specified [" + optPolyATailDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optPolyATail);  
        
        final String optMinimapOptionsDefault = "";
        Option optMinimapOptions = Option.builder("mmopt")
                                    .desc("options for minimap2 [" + optMinimapOptionsDefault + "]\n(`-x` and `-t` are already in use)")
                                    .hasArg(true)
                                    .argName("OPTIONS")
                                    .build();
        options.addOption(optMinimapOptions);

        final String optLongReadOverlapProportionDefault = "0.45";
        Option optLongReadOverlapProportion = Option.builder("lrop")
                                    .desc("minimum proportion of matching bases within long-read overlaps [" + optLongReadOverlapProportionDefault + "]")
                                    .hasArg(true)
                                    .argName("DECIMAL")
                                    .build();
        options.addOption(optLongReadOverlapProportion);
        
        final String optLongReadMinReadDepthDefault = "2";
        Option optLongReadMinReadDepth = Option.builder("lrrd")
                                    .desc("min read depth required for long-read assembly [" + optLongReadMinReadDepthDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optLongReadMinReadDepth);
        
        Option optLongReadPacBioPreset = Option.builder("lrpb")
                                    .desc("use PacBio preset for minimap2 [false]")
                                    .hasArg(false)
                                    .build();
        options.addOption(optLongReadPacBioPreset);
        
        final String optLongReadMaxMergedClusterSizeDefault = "1000";
        Option optLongReadMaxMergedClusterSize = Option.builder("lrmc")
                                    .desc("max merged cluster size for long-read assembly [" + optLongReadMaxMergedClusterSizeDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optLongReadMaxMergedClusterSize);
        
        Option optDebug = Option.builder("debug")
                                    .desc("print debugging information [false]")
                                    .hasArg(false)
                                    .build();
        options.addOption(optDebug);

        /*
        Option optHomopolymerCompressed = Option.builder("hpc")
                                    .desc("use homopolymer-compressed minimizers in long-read clustering [false]\n(Requires `-long`)")
                                    .hasArg(false)
                                    .build();
        options.addOption(optHomopolymerCompressed);
        
        final String optMinimizerSizeDefault = "13";
        Option optMinimizerSize = Option.builder("m")
                                    .longOpt("minimizer")
                                    .desc("minimizer size [" + optMinimizerSizeDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optMinimizerSize);
        
        final String optMinimizerWindowSizeDefault = "15";
        Option optMinimizerWindowSize = Option.builder("mw")
                                    .longOpt("minimizer-window")
                                    .desc("minimizer window size [" + optMinimizerWindowSizeDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optMinimizerWindowSize);
        
        final String optSketchOverlapProportionDefault = "0.7";
        Option optSketchOverlapProportion = Option.builder("sop")
                                    .longOpt("sketch-overlap-proportion")
                                    .desc("minimum proportion of sketch overlap minimizers [" + optSketchOverlapProportionDefault + "]")
                                    .hasArg(true)
                                    .argName("DECIMAL")
                                    .build();
        options.addOption(optSketchOverlapProportion);
        
        final String optSketchOverlapNumberDefault = "30";
        Option optSketchOverlapNumber = Option.builder("son")
                                    .longOpt("sketch-overlap-number")
                                    .desc("minimum number of sketch overlap minimizers [" + optSketchOverlapNumberDefault + "]")
                                    .hasArg(true)
                                    .argName("INT")
                                    .build();
        options.addOption(optSketchOverlapNumber);
        */
        
        Option optHelp = Option.builder("h")
                                    .longOpt("help")
                                    .desc("print this message and exits")
                                    .build();
        options.addOption(optHelp);
        
        Option optVersion = Option.builder("v")
                                    .longOpt("version")
                                    .desc("print version information and exits")
                                    .build();
        options.addOption(optVersion);
        

        try {
            CommandLine line = parser.parse(options, args);
            
            if (line.getOptions().length == 0 || line.hasOption(optHelp.getOpt())) {
                printHelp(options, false);
            }
            
            if (line.hasOption(optVersion.getOpt())) {
                printVersionInfo(true);
            }
            
            System.out.println("RNA-Bloom v" + VERSION + "\n" +
                               "args: " + Arrays.toString(args) + "\n");
            
            String branchFreeExtensionThreshold = line.getOptionValue(optBranchFreeExtensionThreshold.getOpt(), optBranchFreeExtensionDefault);
            if (!isValidStratumName(branchFreeExtensionThreshold)) {
                exitOnError("Unknown stratum name specified, \"" + branchFreeExtensionThreshold + "\"");
            }
            
            final boolean debug = line.hasOption(optDebug.getOpt());
            final int endstage = Integer.parseInt(line.getOptionValue(optStage.getOpt(), optStageDefault));
            final int numThreads = Integer.parseInt(line.getOptionValue(optThreads.getOpt(), optThreadsDefault));
            final boolean forceOverwrite = line.hasOption(optForce.getOpt());
            
            final String name = line.getOptionValue(optName.getOpt(), optNameDefault);
            final String outdir = line.getOptionValue(optOutdir.getOpt(), optOutdirDefault);
            
            System.out.println("name:   " + name);
            System.out.println("outdir: " + outdir);
            
            File f = new File(outdir);
            if (!f.exists()) {
                System.out.println("WARNING: Output directory does not exist!");
                f.mkdirs();
                System.out.println("Created output directory at `" + outdir + "`");
            }
            
            final String graphFile = outdir + File.separator + name + ".graph";
            
            File startedStamp = new File(outdir + File.separator + STAMP_STARTED);
            File dbgDoneStamp = new File(outdir + File.separator + STAMP_DBG_DONE);
            File fragsDoneStamp = new File(outdir + File.separator + STAMP_FRAGMENTS_DONE);
            File txptsDoneStamp = new File(outdir + File.separator + STAMP_TRANSCRIPTS_DONE);
            File txptsNrDoneStamp = new File(outdir + File.separator + STAMP_TRANSCRIPTS_NR_DONE);
            File longReadsCorrectedStamp = new File(outdir + File.separator + STAMP_LONG_READS_CORRECTED);
            File longReadsClusteredStamp = new File(outdir + File.separator + STAMP_LONG_READS_CLUSTERED);
            File longReadsAssembledStamp = new File(outdir + File.separator + STAMP_LONG_READS_ASSEMBLED);
            
            boolean dbgDone = forceOverwrite ? false : dbgDoneStamp.exists();
            boolean fragmentsDone = forceOverwrite ? false : fragsDoneStamp.exists();
            boolean txptsDone = forceOverwrite ? false : txptsDoneStamp.exists();
            boolean txptsNrDone = forceOverwrite ? false : txptsNrDoneStamp.exists();
            boolean longReadsCorrected = forceOverwrite ? false : longReadsCorrectedStamp.exists();
            boolean longReadsClustered = forceOverwrite ? false : longReadsClusteredStamp.exists();
            boolean longReadsAssembled = forceOverwrite ? false : longReadsAssembledStamp.exists();
            
            if (forceOverwrite) {
                if (startedStamp.exists()) {
                    startedStamp.delete();
                }
                
                if (dbgDoneStamp.exists()) {
                    dbgDoneStamp.delete();
                }
                
                if (fragsDoneStamp.exists()) {
                    fragsDoneStamp.delete();
                }
                
                if (txptsDoneStamp.exists()) {
                    txptsDoneStamp.delete();
                }
                
                if (txptsNrDoneStamp.exists()) {
                    txptsNrDoneStamp.delete();
                }
                
                if (longReadsCorrectedStamp.exists()) {
                    longReadsCorrectedStamp.delete();
                }
                
                if (longReadsClusteredStamp.exists()) {
                    longReadsClusteredStamp.delete();
                }
                
                if (longReadsAssembledStamp.exists()) {
                    longReadsAssembledStamp.delete();
                }
            }
                        
            String[] leftReadPaths = line.getOptionValues(optLeftReads.getOpt());
            String[] rightReadPaths = line.getOptionValues(optRightReads.getOpt());
            String[] longReadPaths = line.getOptionValues(optLongReads.getOpt());
            String[] refTranscriptPaths = line.getOptionValues(optRefTranscripts.getOpt());
                        
            final String pooledReadsListFile = line.getOptionValue(optPooledAssembly.getOpt());
            final boolean pooledGraphMode = pooledReadsListFile != null;
            boolean hasLongReadFiles = longReadPaths != null && longReadPaths.length > 0;
            
            if (pooledGraphMode && (leftReadPaths != null || rightReadPaths != null || longReadPaths != null) ) {
                exitOnError("Option `-pool` cannot be used with options `-left`, `-right`, or `-long`");
            }
                                    
            HashMap<String, ArrayList<String>> pooledLeftReadPaths = new HashMap<>();
            HashMap<String, ArrayList<String>> pooledRightReadPaths = new HashMap<>();
            
            float maxBfMem = 0;
            
            if (pooledGraphMode) {                
                System.out.println("Pooled assembly mode is ON!");
                
                if (!new File(pooledReadsListFile).isFile()) {
                    exitOnError("Cannot find pooled read paths list `" + pooledReadsListFile + "`");
                }
                
                System.out.println("Parsing pool reads list file `" + pooledReadsListFile + "`...");
                boolean parseOK = getPooledReadPaths(pooledReadsListFile, pooledLeftReadPaths, pooledRightReadPaths);
                
                if (!parseOK) {
                    exitOnError("Incorrect format of pooled read paths list file!");
                }
                
                int numLeftIds = pooledLeftReadPaths.size();
                int numRightIds = pooledRightReadPaths.size();
                
                if (numLeftIds != numRightIds) {
                    exitOnError("Pooled read paths list file has disagreeing number of sample IDs for left (" + numLeftIds + ") and right (" + numRightIds + ") reads!");
                }
                
                if (numLeftIds == 0) {
                    exitOnError("Pooled read paths list file is empty!");
                }
                
                ArrayList<String> leftPathsQueue = new ArrayList<>();
                ArrayList<String> rightPathsQueue = new ArrayList<>();
                
                for (String id : pooledLeftReadPaths.keySet()) {
                    leftPathsQueue.addAll(pooledLeftReadPaths.get(id));
                    rightPathsQueue.addAll(pooledRightReadPaths.get(id));
                }
                
                leftReadPaths = new String[leftPathsQueue.size()];
                rightReadPaths = new String[rightPathsQueue.size()];
                
                leftPathsQueue.toArray(leftReadPaths);
                rightPathsQueue.toArray(rightReadPaths);
                
                checkInputFileFormat(leftReadPaths);
                checkInputFileFormat(rightReadPaths);
                
                double readFilesTotalBytes = 0;

                for (String fq : leftReadPaths) {
                    readFilesTotalBytes += new File(fq).length();
                }
                for (String fq : rightReadPaths) {
                    readFilesTotalBytes += new File(fq).length();
                }
                
                maxBfMem = (float) Float.parseFloat(line.getOptionValue(optAllMem.getOpt(), Float.toString((float) (Math.max(NUM_BYTES_1MB * 100, readFilesTotalBytes) / NUM_BYTES_1GB))));
            }
            else if (longReadPaths != null && longReadPaths.length > 0) {
                if (isListFile(longReadPaths)) {
                    // input path is a list file
                    longReadPaths = getPathsFromListFile(longReadPaths);
                }
                
                checkInputFileFormat(longReadPaths);
                
                double readFilesTotalBytes = 0;

                for (String fq : longReadPaths) {
                    readFilesTotalBytes += new File(fq).length();
                }
                
                maxBfMem = (float) Float.parseFloat(line.getOptionValue(optAllMem.getOpt(), Float.toString((float) (Math.max(NUM_BYTES_1MB * 100, readFilesTotalBytes) / NUM_BYTES_1GB))));
            }
            else {
                double readFilesTotalBytes = 0;
                
                if (leftReadPaths == null || leftReadPaths.length == 0) {
                    exitOnError("Please specify left read files!");
                }

//                if (rightReadPaths == null || rightReadPaths.length == 0) {
//                    exitOnError("Please specify right read files!");
//                }

                if (leftReadPaths != null && rightReadPaths != null &&
                        leftReadPaths.length > 0 && rightReadPaths.length > 0 &&
                        leftReadPaths.length != rightReadPaths.length) {
                    exitOnError("Read files are not paired properly!");
                }
                
                if (isListFile(leftReadPaths)) {
                    // input path is a list file
                    leftReadPaths = getPathsFromListFile(leftReadPaths);
                }
                
                checkInputFileFormat(leftReadPaths);
                
                for (String p : leftReadPaths) {
                    readFilesTotalBytes += new File(p).length();
                }
                
                if (rightReadPaths != null && rightReadPaths.length > 0) {
                    if (isListFile(rightReadPaths)) {
                        // input path is a list file
                        rightReadPaths = getPathsFromListFile(rightReadPaths);
                    }
                    
                    checkInputFileFormat(rightReadPaths);
                    
                    for (String p : rightReadPaths) {
                        readFilesTotalBytes += new File(p).length();
                    }
                }
                                
                maxBfMem = (float) Float.parseFloat(line.getOptionValue(optAllMem.getOpt(), Float.toString((float) (Math.max(NUM_BYTES_1MB * 100, readFilesTotalBytes) / NUM_BYTES_1GB))));
            }
            
            boolean hasLeftReadFiles = leftReadPaths != null && leftReadPaths.length > 0;
            boolean hasRightReadFiles = rightReadPaths != null && rightReadPaths.length > 0;

            boolean hasRefTranscriptFiles = refTranscriptPaths != null && refTranscriptPaths.length > 0;
            if (hasRefTranscriptFiles && isListFile(refTranscriptPaths)) {
                // input path is a list file
                refTranscriptPaths = getPathsFromListFile(refTranscriptPaths);
            }
            
            final boolean revCompLeft = line.hasOption(optRevCompLeft.getOpt());
            final boolean revCompRight = line.hasOption(optRevCompRight.getOpt());
            final boolean revCompLong = line.hasOption(optRevCompLong.getOpt());
            final boolean strandSpecific = line.hasOption(optStranded.getOpt());
            final boolean writeUracil = line.hasOption(optUracil.getOpt());
            final boolean mergePool = line.hasOption(optMergePool.getOpt());
            final boolean outputNrTxpts = mergePool ? true : !line.hasOption(optNoReduce.getOpt());
            final String minimapOptions = line.getOptionValue(optMinimapOptions.getOpt(), optMinimapOptionsDefault);
            final boolean usePacBioPreset = line.hasOption(optLongReadPacBioPreset.getOpt());
            /*
            final boolean useCompressedMinimizers = line.hasOption(optHomopolymerCompressed.getOpt());
            final int minimizerSize = Integer.parseInt(line.getOptionValue(optMinimizerSize.getOpt(), optMinimizerSizeDefault));
            final int minimizerWindowSize = Integer.parseInt(line.getOptionValue(optMinimizerWindowSize.getOpt(), optMinimizerWindowSizeDefault));
            final float minSketchOverlapProportion = Float.parseFloat(line.getOptionValue(optSketchOverlapProportion.getOpt(), optSketchOverlapProportionDefault));
            final int minSketchOverlapNumber = Integer.parseInt(line.getOptionValue(optSketchOverlapNumber.getOpt(), optSketchOverlapNumberDefault));
            */
            
            if ((hasLongReadFiles || outputNrTxpts || mergePool) && !hasMinimap2()) {
                exitOnError("`minimap2` not found in PATH!");
            }

            if (hasLongReadFiles && !hasRacon()) {
                exitOnError("`racon` not found in PATH!");
            }
            
            if (mergePool && !pooledGraphMode) {
                exitOnError("`-mergepool` option requires `-pool` to be used!");
            }
                        
            String defaultPercentIdentity = hasLongReadFiles ? defaultPercentIdentityLR : optPercentIdentityDefault;
            final float percentIdentity = Float.parseFloat(line.getOptionValue(optPercentIdentity.getOpt(), defaultPercentIdentity));
            
            String defaultMaxIndelSize = hasLongReadFiles ? defaultMaxIndelSizeLR : optIndelSizeDefault;
            final int maxIndelSize = Integer.parseInt(line.getOptionValue(optIndelSize.getOpt(), defaultMaxIndelSize));
            
            String defaultMaxErrCorrItr = hasLongReadFiles ? defaultMaxErrorCorrItrLR : optErrCorrItrDefault;
            final int maxErrCorrItr = Integer.parseInt(line.getOptionValue(optErrCorrItr.getOpt(), defaultMaxErrCorrItr));
            
            String defaultMinKmerCov = hasLongReadFiles ? defaultMinCoverageLR : optMinKmerCovDefault;
            int minKmerCov = Integer.parseInt(line.getOptionValue(optMinKmerCov.getOpt(), defaultMinKmerCov));
                        
            String defaultMaxTipLen = hasLongReadFiles ? defaultMaxTipLengthLR : optTipLengthDefault;
            final int maxTipLen = Integer.parseInt(line.getOptionValue(optTipLength.getOpt(), defaultMaxTipLen));
            
            final float longReadOverlapProportion = Float.parseFloat(line.getOptionValue(optLongReadOverlapProportion.getOpt(), optLongReadOverlapProportionDefault));
            final int longReadMinReadDepth = Integer.parseInt(line.getOptionValue(optLongReadMinReadDepth.getOpt(), optLongReadMinReadDepthDefault));
            final int maxMergedClusterSize = Integer.parseInt(line.getOptionValue(optLongReadMaxMergedClusterSize.getOpt(), optLongReadMaxMergedClusterSizeDefault));
            
            final int qDBG = Integer.parseInt(line.getOptionValue(optBaseQualDbg.getOpt(), optBaseQualDbgDefault));
            final int qFrag = Integer.parseInt(line.getOptionValue(optBaseQualFrag.getOpt(), optBaseQualFragDefault));
                        
            float sbfGB = Float.parseFloat(line.getOptionValue(optSbfMem.getOpt(), Float.toString(maxBfMem * 1f / 8f)));
            float dbgGB = Float.parseFloat(line.getOptionValue(optDbgbfMem.getOpt(), Float.toString(maxBfMem * 1f / 8f)));
            float cbfGB = Float.parseFloat(line.getOptionValue(optCbfMem.getOpt(), Float.toString(maxBfMem * 4f / 8f)));
            float pkbfGB = Float.parseFloat(line.getOptionValue(optPkbfMem.getOpt(), Float.toString(maxBfMem * 1f / 8f)));
                        
            long sbfSize = (long) (NUM_BITS_1GB * sbfGB);
            long dbgbfSize = (long) (NUM_BITS_1GB * dbgGB);
            long cbfSize = (long) (NUM_BYTES_1GB * cbfGB);
            long pkbfSize = (long) (NUM_BITS_1GB * pkbfGB);
            
            final int allNumHash = Integer.parseInt(line.getOptionValue(optAllHash.getOpt(), optAllHashDefault));
            final String allNumHashStr = Integer.toString(allNumHash);
            final int sbfNumHash = Integer.parseInt(line.getOptionValue(optSbfHash.getOpt(), allNumHashStr));
            final int dbgbfNumHash = Integer.parseInt(line.getOptionValue(optDbgbfHash.getOpt(), allNumHashStr));
            final int cbfNumHash = Integer.parseInt(line.getOptionValue(optCbfHash.getOpt(), allNumHashStr));
            final int pkbfNumHash = Integer.parseInt(line.getOptionValue(optPkbfHash.getOpt(), allNumHashStr));
            
            final float maxFPR = Float.parseFloat(line.getOptionValue(optFpr.getOpt(), optFprDefault));
            final boolean saveGraph = line.hasOption(optSaveBf.getOpt());
            boolean storeReadPairedKmers = !hasLongReadFiles && (hasLeftReadFiles || hasRightReadFiles || hasRefTranscriptFiles);
            
            boolean useNTCard = line.hasOption(optNtcard.getOpt());
            
            String defaultK = hasLongReadFiles ? defaultKmerSizeLR : optKmerSizeDefault;
            String kArg = line.getOptionValue(optKmerSize.getOpt(), defaultK);
            int k = Integer.parseInt(defaultK);
            
            int[] kmerSizes = null;
            try {
                kmerSizes = getKmerSizes(kArg);
            }
            catch(NumberFormatException e) {
                exitOnError("Invalid k-mer size: " + kArg);
            }
            
            switch (kmerSizes.length) {
                case 0:
                    exitOnError("Invalid k-mer size: " + kArg);
                    break;
                case 1:
                    k = kmerSizes[0];
                    break;
                default:
                    if (!useNTCard) {
                        System.out.println("\nTurning on option `-ntcard` to find optimal k in " + kArg);
                        useNTCard = true;
                    }
                    break;
            }
            
            long expNumKmers = -1L;
            NTCardHistogram hist = null;
            if (useNTCard) {
                if (!hasNtcard()) {
                    exitOnError("`ntcard` not found in your PATH!");
                }
                
                System.out.println("\nK-mer counting with ntCard...");
                timer.start();
                 
                String ntcard_reads_list_file = outdir + File.separator + name + ".ntcard.readslist.txt";
                BufferedWriter writer = new BufferedWriter(new FileWriter(ntcard_reads_list_file, false));
                
                if (hasLeftReadFiles && leftReadPaths != null) {
                    for (String p : leftReadPaths) {
                        writer.write(p + '\n');
                    }
                }
                
                if (hasRightReadFiles && rightReadPaths != null) {
                    for (String p : rightReadPaths) {
                        writer.write(p + '\n');
                    }
                }
                
                if (hasLongReadFiles && longReadPaths != null) {
                    for (String p : longReadPaths) {
                        writer.write(p + '\n');
                    }
                }
                
                if (hasRefTranscriptFiles && refTranscriptPaths != null) {
                    for (String p : refTranscriptPaths) {
                        writer.write(p + '\n');
                    }
                }
                
                writer.close();
                
                String histogramPathPrefix = outdir + File.separator + name;
                
                long numUniqueNonSingletons = -1L;
                
                for (int tmpK : kmerSizes) {                    
                    NTCardHistogram tmpHist = getNTCardHistogram(numThreads, tmpK, histogramPathPrefix, ntcard_reads_list_file, forceOverwrite);
                    if (tmpHist == null) {
                        exitOnError("Error running ntCard!");
                    }
                    long tmpNumUniqueNonSingletons = tmpHist.numUniqueKmers - tmpHist.getNumSingletons();
                    
                    System.out.println("Unique k-mers (k=" + tmpK + "):     " + NumberFormat.getInstance().format(tmpHist.numUniqueKmers));
                    System.out.println("Unique k-mers (k=" + tmpK + ",c>1): " + NumberFormat.getInstance().format(tmpNumUniqueNonSingletons));
                    
                    if (tmpNumUniqueNonSingletons > numUniqueNonSingletons) {
                        hist = tmpHist;
                        k = tmpK;
                        expNumKmers = tmpHist.numUniqueKmers;
                        numUniqueNonSingletons = tmpNumUniqueNonSingletons;
                    }
                    else {
                        // optimal k is found
                        break;
                    }
                }
                
                if (kmerSizes.length > 1) {
                    System.out.println("Setting k to " + k);
                }
                
//                if (hasLongReadFiles) {
//                    if (!line.hasOption(optMinKmerCov.getOpt())) {
//                        minKmerCov = hist.getMinCovThreshold(3);
//                    }
//                    
//                    System.out.println("Min k-mer coverage threshold: " + NumberFormat.getInstance().format(minKmerCov));
//                }
                    
                if (expNumKmers <= 0) {
                    exitOnError("Cannot get number of unique k-mers from ntCard! (" + expNumKmers + ")");
                }
                
                System.out.println("K-mer counting completed in " + MyTimer.hmsFormat(timer.elapsedMillis()));                
            }
            else {
                expNumKmers = Long.parseLong(line.getOptionValue(optNumKmers.getOpt(), "-1"));
                System.out.println("Min k-mer coverage threshold: " + NumberFormat.getInstance().format(minKmerCov));
            }
            
            String defaultMinOverlap = hasLongReadFiles ? defaultMinOverlapLR : Integer.toString(k-1);
            final int minOverlap = Integer.parseInt(line.getOptionValue(optOverlap.getOpt(), defaultMinOverlap));
            
            if (expNumKmers > 0) {
                sbfSize = BloomFilter.getExpectedSize(expNumKmers, maxFPR, sbfNumHash);
                sbfGB = sbfSize / (float) NUM_BITS_1GB;
                
                dbgbfSize = BloomFilter.getExpectedSize(expNumKmers, maxFPR, dbgbfNumHash);
                dbgGB = dbgbfSize / (float) NUM_BITS_1GB;
                
                if (hist != null) {
                    cbfSize = CountingBloomFilter.getExpectedSize(expNumKmers-hist.getNumSingletons(), maxFPR, cbfNumHash);
                    cbfGB = cbfSize / (float) NUM_BYTES_1GB;
                }
                else {
                    cbfSize = CountingBloomFilter.getExpectedSize(expNumKmers, maxFPR, cbfNumHash);
                    cbfGB = cbfSize / (float) NUM_BYTES_1GB;
                }
                
                pkbfSize = PairedKeysBloomFilter.getExpectedSize(expNumKmers, maxFPR, pkbfNumHash);
                pkbfGB = pkbfSize / (float) NUM_BITS_1GB;
            }
            
            /**@TODO ensure that sbfNumHash and pkbfNumHash <= max(dbgbfNumHash, cbfNumHash) */
            
            final int sampleSize = Integer.parseInt(line.getOptionValue(optSample.getOpt(), optSampleDefault));
            final int bound = Integer.parseInt(line.getOptionValue(optBound.getOpt(), optBoundDefault));
            final int lookahead = Integer.parseInt(line.getOptionValue(optLookahead.getOpt(), optLookaheadDefault));
            final float maxCovGradient = Float.parseFloat(line.getOptionValue(optMaxCovGrad.getOpt(), optMaxCovGradDefault));
            
            String defaultMinTranscriptLength = hasLongReadFiles ? defaultMinTranscriptLengthLR : optMinLengthDefault;
            final int minTranscriptLength = Integer.parseInt(line.getOptionValue(optMinLength.getOpt(), defaultMinTranscriptLength));
            
            final int minPolyATail = Integer.parseInt(line.getOptionValue(optPolyATail.getOpt(), optPolyATailDefault));
//            if (minPolyATail > 0) {
//                maxErrCorrItr = 0;
//                branchFreeExtensionThreshold = STRATUM_01;
//            }
            
            boolean keepArtifact = line.hasOption(optKeepArtifact.getOpt());
            boolean keepChimera = line.hasOption(optKeepChimera.getOpt());
            final boolean sensitiveMode = line.hasOption(optSensitive.getOpt());
            if (sensitiveMode) {
                branchFreeExtensionThreshold = STRATUM_01;
                keepArtifact = true;
                keepChimera = true;
            }
            
            final boolean noFragDBG = false; //line.hasOption(optNoFragDBG.getOpt());
            final boolean reqFragKmersConsistency = !line.hasOption(optNoFragmentsConsistency.getOpt());
            final boolean extendFragments = line.hasOption(optExtend.getOpt());
            final int minNumKmerPairs = Integer.parseInt(line.getOptionValue(optMinKmerPairs.getOpt(), optMinKmerPairsDefault));
            final String txptNamePrefix = line.getOptionValue(optPrefix.getOpt(), optPrefixDefault);

            if (!storeReadPairedKmers) {
                pkbfGB = 0;
            }
            
            if (hasLongReadFiles) {
                sbfGB = 0;
            }
                        
            RNABloom assembler = new RNABloom(k, qDBG, qFrag, debug);
            assembler.setParams(strandSpecific, maxTipLen, lookahead, maxCovGradient, maxIndelSize, percentIdentity, minNumKmerPairs, minPolyATail);

            FileWriter writer = new FileWriter(startedStamp, false);
            writer.write(String.join(" ", args));
            writer.close();
            
            if (endstage >= 1 &&
                    ((!txptsDone && !hasLongReadFiles) ||
                    (!fragmentsDone && hasLeftReadFiles && hasRightReadFiles) || 
                    (hasLongReadFiles && !longReadsCorrected))) {
                
                if (dbgDone) {
                    System.out.println("WARNING: Graph was already constructed (k=" + k + ")!");

                    if (endstage == 1) {
                        System.exit(0);
                    }

                    if (!fragmentsDone || (outputNrTxpts && !txptsNrDone) || !txptsDone) {
                        System.out.println("Loading graph from file `" + graphFile + "`...");
                        assembler.restoreGraph(new File(graphFile), noFragDBG || !fragmentsDone || (outputNrTxpts && !txptsNrDone));
                    }
                }
                else {
                    printBloomFilterMemoryInfo(dbgGB, cbfGB, pkbfGB, sbfGB);
                    
                    ArrayList<String> forwardFilesList = new ArrayList<>();
                    ArrayList<String> backwardFilesList = new ArrayList<>();
                    ArrayList<String> longFilesList = new ArrayList<>();
                    ArrayList<String> refFilesList = new ArrayList<>();

                    if (hasLeftReadFiles) {
                        if (revCompLeft) {
                            backwardFilesList.addAll(Arrays.asList(leftReadPaths));
                        }
                        else {
                            forwardFilesList.addAll(Arrays.asList(leftReadPaths));
                        }
                    }

                    if (hasRightReadFiles) {
                        if (revCompRight) {
                            backwardFilesList.addAll(Arrays.asList(rightReadPaths));
                        }
                        else {
                            forwardFilesList.addAll(Arrays.asList(rightReadPaths));
                        }
                    }

                    if (hasLongReadFiles) {
                        longFilesList.addAll(Arrays.asList(longReadPaths));
                    }

                    if (hasRefTranscriptFiles) {
                        refFilesList.addAll(Arrays.asList(refTranscriptPaths));
                    }

                    System.out.println("\n> Stage 1: Construct graph from reads (k=" + k + ")");
                    timer.start();

                    assembler.initializeGraph(strandSpecific, 
                            dbgbfSize, cbfSize, pkbfSize, 
                            dbgbfNumHash, cbfNumHash, pkbfNumHash, false, storeReadPairedKmers);

                    if (!hasLongReadFiles) {
                        assembler.setupKmerScreeningBloomFilter(sbfSize, sbfNumHash);
                    }

                    assembler.populateGraph(forwardFilesList, backwardFilesList, longFilesList, refFilesList, strandSpecific, revCompLong, numThreads, false, storeReadPairedKmers);

                    if (!useNTCard && !assembler.withinMaxFPR(maxFPR)) {
                        System.out.println("WARNING: Bloom filter FPR is higher than the maximum allowed FPR (" + maxFPR*100 +"%)!");

                        System.out.println("Adjusting Bloom filter sizes...");

                        long[] suggestedSizes = assembler.getOptimalBloomFilterSizes(maxFPR, sbfNumHash, dbgbfNumHash, cbfNumHash, pkbfNumHash);

                        assembler.destroyAllBf();

                        dbgbfSize = suggestedSizes[1];
                        cbfSize = suggestedSizes[2];
                        pkbfSize = suggestedSizes[3];
                        sbfSize = suggestedSizes[0];

                        dbgGB = dbgbfSize / (float) NUM_BITS_1GB;
                        cbfGB = cbfSize / (float) NUM_BYTES_1GB;
                        pkbfGB = pkbfSize / (float) NUM_BITS_1GB;
                        sbfGB = sbfSize / (float) NUM_BITS_1GB;

                        if (!storeReadPairedKmers) {
                            pkbfGB = 0;
                        }

                        if (hasLongReadFiles) {
                            sbfGB = 0;
                        }

                        printBloomFilterMemoryInfo(dbgGB, cbfGB, pkbfGB, sbfGB);

                        assembler.initializeGraph(strandSpecific, 
                                dbgbfSize, cbfSize, pkbfSize, 
                                dbgbfNumHash, cbfNumHash, pkbfNumHash, false, storeReadPairedKmers);

                        if (!hasLongReadFiles) {
                            assembler.setupKmerScreeningBloomFilter(sbfSize, sbfNumHash);
                        }

                        System.out.println("Repopulate graph...");

                        assembler.populateGraph(forwardFilesList, backwardFilesList, longFilesList, refFilesList, strandSpecific, revCompLong, numThreads, false, storeReadPairedKmers);
                    }    

                    if (saveGraph) {
                        System.out.println("Saving graph to disk `" + graphFile + "`...");
                        assembler.saveGraph(new File(graphFile));
                        touch(dbgDoneStamp);
                    }

                    System.out.println("> Stage 1 completed in " + MyTimer.hmsFormat(timer.elapsedMillis()));

                    if (endstage <= 1) {
                        System.out.println("Total runtime: " + MyTimer.hmsFormat(timer.totalElapsedMillis()));
                        System.exit(0);
                    }
                }
            }           

            if (pooledGraphMode) {
                // assemble fragments for each sample
                int numSamples = pooledLeftReadPaths.size();
                int sampleId = 0;
                
                System.out.println("\n> Stage 2: Assemble fragments for " + numSamples + " samples");
                MyTimer stageTimer = new MyTimer();
                stageTimer.start();
                
                String[] sampleNames = new String[numSamples];
                pooledLeftReadPaths.keySet().toArray(sampleNames);
                Arrays.sort(sampleNames);
                
                for (String sampleName : sampleNames) {
                    System.out.println(">> Working on \"" + sampleName + "\" (sample " + ++sampleId + " of " + numSamples + ")...");
                    
                    ArrayList<String> lefts = pooledLeftReadPaths.get(sampleName);
                    ArrayList<String> rights = pooledRightReadPaths.get(sampleName);
                    
                    FastxFilePair[] fqPairs = new FastxFilePair[lefts.size()];
                    for (int i=0; i<lefts.size(); ++i) {
                        fqPairs[i] = new FastxFilePair(lefts.get(i), rights.get(i), revCompLeft, revCompRight);
                    }

                    String sampleOutdir = outdir + File.separator + sampleName;
                    new File(sampleOutdir).mkdirs();
                    
                    
                    MyTimer sampleTimer = new MyTimer();
                    sampleTimer.start();
                    
                    assembleFragments(assembler, forceOverwrite,
                                    sampleOutdir, sampleName, fqPairs,
                                    sbfSize, pkbfSize, sbfNumHash, pkbfNumHash, numThreads,
                                    bound, minOverlap, sampleSize, maxErrCorrItr, extendFragments, minKmerCov, keepArtifact);
                    
                    System.out.println(">> Fragments assembled in " + MyTimer.hmsFormat(sampleTimer.elapsedMillis()) + "\n");
                }
                
                System.out.println("> Stage 2 completed in " + MyTimer.hmsFormat(stageTimer.elapsedMillis()));
                
                touch(fragsDoneStamp);
                
                if (endstage <= 2) {
                    System.out.println("Total runtime: " + MyTimer.hmsFormat(timer.totalElapsedMillis()));
                    System.exit(0);
                }
                
                // assemble transcripts for each sample
                sampleId = 0;
                System.out.println("\n> Stage 3: Assemble transcripts for " + numSamples + " samples");
                stageTimer.start();
                                
                for (String sampleName : sampleNames) {
                    System.out.println(">> Working on \"" + sampleName + "\" (sample " + ++sampleId + " of " + numSamples + ")...");
                    
                    String sampleOutdir = outdir + File.separator + sampleName;
                    
                    assembleTranscriptsPE(assembler, forceOverwrite,
                                    sampleOutdir, sampleName, txptNamePrefix,
                                    sbfSize, sbfNumHash, pkbfSize, pkbfNumHash, numThreads, noFragDBG,
                                    sampleSize, minTranscriptLength, keepArtifact, keepChimera,
                                    reqFragKmersConsistency, true, minKmerCov,
                                    branchFreeExtensionThreshold, outputNrTxpts, minPolyATail > 0, writeUracil,
                                    refTranscriptPaths, usePacBioPreset);
                    
                    System.out.print("\n");
                }
                
                if (mergePool) {
                    // combine assembly files
                    
                    System.out.println(">> Merging transcripts from all samples...");
                    String txptFileExt = ".nr" + FASTA_EXT;
                    String shortTxptFileExt = ".nr.short" + FASTA_EXT;
                    
                    if (txptsNrDone) {
                        System.out.println("WARNING: Assemblies were already merged!");
                    }
                    else {
                        MyTimer mergeTimer = new MyTimer();
                        mergeTimer.start();
                        
                        boolean ok = mergePooledAssemblies(outdir, name, sampleNames, 
                                        txptFileExt, shortTxptFileExt, txptNamePrefix,
                                        k, numThreads, strandSpecific, maxIndelSize,
                                        maxTipLen, percentIdentity, !keepArtifact, 
                                        minTranscriptLength, writeUracil, usePacBioPreset);
                        
                        if (ok) {
                            System.out.println("Merged assembly at `" + outdir + File.separator + name + ".transcripts.fa`");
                            System.out.println(">> Merging completed in " + MyTimer.hmsFormat(mergeTimer.elapsedMillis()));
                        }
                        else {
                            exitOnError("Error during assembly merging!");
                        }
                    }
                    
                    touch(txptsNrDoneStamp);
                }
                
                System.out.println("> Stage 3 completed in " + MyTimer.hmsFormat(stageTimer.elapsedMillis()));                
                
                touch(txptsDoneStamp);
            }
            else if (hasLongReadFiles) {
                final String correctedLongReadFilePrefix = outdir + File.separator + name + ".longreads.corrected";

                /*
                final int numCovStrata = COVERAGE_ORDER.length;
                final int numLenStrata = LENGTH_STRATUM_NAMES.length;
                String[][] correctedLongReadFileNames = new String[numCovStrata][numLenStrata];
                for (int c=0; c<numCovStrata; ++c) {
                    String covStratumName = COVERAGE_ORDER[c];
                    for (int l=0; l<numLenStrata; ++l) {
                        String lengthStratumName = LENGTH_STRATUM_NAMES[l];

                        String correctedLongReadsFasta = correctedLongReadFilePrefix + "." + covStratumName + "." + lengthStratumName + FASTA_EXT;
                        correctedLongReadFileNames[c][l] = correctedLongReadsFasta;
                    }
                }
                */
                
                String longCorrectedReadsPath = correctedLongReadFilePrefix + ".long" + FASTA_EXT;
                String shortCorrectedReadsPath = correctedLongReadFilePrefix + ".short" + FASTA_EXT;
                String repeatReadsFileName = correctedLongReadFilePrefix + ".repeats" + FASTA_EXT;
                String numCorrectedReadsPath = correctedLongReadFilePrefix + ".count";
                long numCorrectedReads = 0;
                
                System.out.println("\n> Stage 2: Correct long reads for \"" + name + "\"");
                MyTimer stageTimer = new MyTimer();
                stageTimer.start();
                
                if (longReadsCorrected) {
                    System.out.println("WARNING: Reads were already corrected!");
                    numCorrectedReads = Long.parseLong(loadStringFromFile(numCorrectedReadsPath));
                }
                else {
                    /* set up the file writers */
                    /*
                    for (String[] row : correctedLongReadFileNames) {
                        for (String fasta : row) {
                            Files.deleteIfExists(FileSystems.getDefault().getPath(fasta));
                        }
                    }
                    
                    Files.deleteIfExists(FileSystems.getDefault().getPath(repeatReadsFileName));
                    
                    correctLongReads(assembler,
                            longReadPaths, correctedLongReadFileNames, repeatReadsFileName,
                            maxErrCorrItr, minKmerCov, numThreads, sampleSize, minTranscriptLength,
                            revCompLong, !keepArtifact);
                    */
                    
                    numCorrectedReads = correctLongReads(assembler, 
                            longReadPaths, longCorrectedReadsPath, shortCorrectedReadsPath, repeatReadsFileName,
                            maxErrCorrItr, minKmerCov, numThreads, sampleSize, minTranscriptLength,
                            revCompLong, !keepArtifact, writeUracil);
                    
                    saveStringToFile(numCorrectedReadsPath, Long.toString(numCorrectedReads));
                    
                    touch(longReadsCorrectedStamp);
                }
                
                System.out.println("> Stage 2 completed in " + MyTimer.hmsFormat(stageTimer.elapsedMillis()));
                
                if (endstage <= 2) {
                    System.out.println("Total runtime: " + MyTimer.hmsFormat(timer.totalElapsedMillis()));
                    System.exit(0);
                }
                
                /*
                final String clusteredLongReadsDirectory = outdir + File.separator + name + ".longreads.clusters";
                
                System.out.println("\n> Stage 3: Cluster long reads for \"" + name + "\"");
                stageTimer.start();
                
                if (longReadsClustered) {
                    System.out.println("WARNING: Reads were already clustered!");
                }
                else {
                    int minSketchSize = Math.max(minSketchOverlapNumber, ((minTranscriptLength-minimizerSize+1)-minimizerWindowSize+1)/minimizerWindowSize);
                    
                    clusterLongReads(assembler,
                            correctedLongReadFileNames, clusteredLongReadsDirectory,
                            minSketchSize, numThreads, useCompressedMinimizers,
                            minimizerSize, minimizerWindowSize, minSketchOverlapProportion, minSketchOverlapNumber);
                    
                    touch(longReadsClusteredStamp);
                }
                
                System.out.println("Stage 3 completed in " + MyTimer.hmsFormat(stageTimer.elapsedMillis()));
                
                if (endstage <= 3) {
                    System.out.println("Total runtime: " + MyTimer.hmsFormat(timer.totalElapsedMillis()));
                    System.exit(0);
                }
                */
                
                System.out.println("\n> Stage 3: Assemble long reads for \"" + name + "\"");
                stageTimer.start();
                
                if (longReadsAssembled) {
                    System.out.println("WARNING: Reads were already assembled!");
                }
                else {
                    assembler.destroyAllBf();
                    
                    /*
                    final String assembledLongReadsDirectory = outdir + File.separator + name + ".longreads.assembly";
                    final String assembledLongReadsCombinedFile = outdir + File.separator + name + ".transcripts" + FASTA_EXT;
                    boolean ok = assembleLongReads(assembler,
                            clusteredLongReadsDirectory, assembledLongReadsDirectory, assembledLongReadsCombinedFile,
                            numThreads, forceOverwrite, writeUracil, minimapOptions, minKmerCov, txptNamePrefix, strandSpecific, minTranscriptLength, !keepArtifact);
                    */

                    final String clusteredLongReadsDirectory = outdir + File.separator + name + ".longreads.clusters";
                    final String assembledTranscriptsPath = outdir + File.separator + name + ".transcripts" + FASTA_EXT;
                    
                    boolean ok = assembleClusteredLongReads(assembler,
                            longCorrectedReadsPath, clusteredLongReadsDirectory,
                            assembledTranscriptsPath, writeUracil, 
                            numThreads, forceOverwrite, minimapOptions, minKmerCov, 
                            maxTipLen, longReadOverlapProportion, minOverlap,
                            txptNamePrefix, strandSpecific, !keepArtifact,
                            longReadMinReadDepth, usePacBioPreset, maxMergedClusterSize);
                    
                    
                    if (ok) {
                        touch(longReadsAssembledStamp);
                    }
                    else {
                        exitOnError("Error assembling long reads!");
                    }
                }
                
//                if (outputNrTxpts) {
//                    generateNonRedundantTranscripts(assembler, forceOverwrite, outdir, name, sbfSize, sbfNumHash);
//                }
                
                System.out.println("> Stage 3 completed in " + MyTimer.hmsFormat(stageTimer.elapsedMillis()));
            }
            else if (hasLeftReadFiles && !hasRightReadFiles) {
                // Note: no stage 2
                System.out.println("\n> Skipping Stage 2 for single-end reads.");
                
                System.out.println("\n> Stage 3: Assemble transcripts for \"" + name + "\"");
                MyTimer stageTimer = new MyTimer();
                stageTimer.start();
                
                assembleTranscriptsSE(assembler, forceOverwrite,
                                    outdir, name, txptNamePrefix,
                                    sbfSize, sbfNumHash, numThreads, 
                                    leftReadPaths, revCompLeft,
                                    minTranscriptLength, keepArtifact, keepChimera,
                                    minKmerCov,
                                    outputNrTxpts, writeUracil, usePacBioPreset);
                
                System.out.println("> Stage 3 completed in " + MyTimer.hmsFormat(stageTimer.elapsedMillis()));
            }
            else {
                FastxFilePair[] fqPairs = new FastxFilePair[leftReadPaths.length];
                for (int i=0; i<leftReadPaths.length; ++i) {
                    fqPairs[i] = new FastxFilePair(leftReadPaths[i], rightReadPaths[i], revCompLeft, revCompRight);
                }

                System.out.println("\n> Stage 2: Assemble fragments for \"" + name + "\"");
                MyTimer stageTimer = new MyTimer();
                stageTimer.start();
                
                assembleFragments(assembler, forceOverwrite,
                                    outdir, name, fqPairs,
                                    sbfSize, pkbfSize, sbfNumHash, pkbfNumHash, numThreads,
                                    bound, minOverlap, sampleSize, maxErrCorrItr, extendFragments, minKmerCov, keepArtifact);
                
                System.out.println("> Stage 2 completed in " + MyTimer.hmsFormat(stageTimer.elapsedMillis()));
                
                if (endstage <= 2) {
                    System.out.println("Total runtime: " + MyTimer.hmsFormat(timer.totalElapsedMillis()));
                    System.exit(0);
                }

                System.out.println("\n> Stage 3: Assemble transcripts for \"" + name + "\"");
                stageTimer.start();
                
                assembleTranscriptsPE(assembler, forceOverwrite,
                                outdir, name, txptNamePrefix,
                                sbfSize, sbfNumHash, pkbfSize, pkbfNumHash, numThreads, noFragDBG,
                                sampleSize, minTranscriptLength, keepArtifact, keepChimera,
                                reqFragKmersConsistency, true, minKmerCov, 
                                branchFreeExtensionThreshold, outputNrTxpts, minPolyATail > 0, writeUracil,
                                refTranscriptPaths, usePacBioPreset);
                
                System.out.println("> Stage 3 completed in " + MyTimer.hmsFormat(stageTimer.elapsedMillis()));
            }      
        }
        catch (Exception exp) {
            handleException(exp);
        }
        
        System.out.println("Total runtime: " + MyTimer.hmsFormat(timer.totalElapsedMillis()));
    }
}
