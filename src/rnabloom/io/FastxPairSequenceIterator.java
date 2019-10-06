/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package rnabloom.io;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 *
 * @author gengar
 */
public class FastxPairSequenceIterator {
    private final FastxFilePair[] fastxPairs;
    private int fileCursor;
    private final Pattern seqPattern;
    private final Pattern qualPattern;
    private FastxPairReader reader;
    
    public FastxPairSequenceIterator(FastxFilePair[] fastxPairs, Pattern seqPattern, Pattern qualPattern) throws IOException {
        this.seqPattern = seqPattern;
        this.qualPattern = qualPattern;
        this.fastxPairs = fastxPairs;
        this.fileCursor = 0;
        setReader(fastxPairs[fileCursor]);
    }
    
    private void setReader(FastxFilePair fxPair) throws IOException {
        if (FastqReader.isCorrectFormat(fxPair.leftPath) && FastqReader.isCorrectFormat(fxPair.rightPath)) {
            reader = new FastqPairReader(fxPair.leftPath, fxPair.rightPath, qualPattern, seqPattern, fxPair.leftRevComp, fxPair.rightRevComp);
        }
        else if (FastaReader.isCorrectFormat(fxPair.leftPath) && FastaReader.isCorrectFormat(fxPair.rightPath)) {
            reader = new FastaPairReader(fxPair.leftPath, fxPair.rightPath, seqPattern, fxPair.leftRevComp, fxPair.rightRevComp);
        }
        else {
            throw new FileFormatException("Incompatible file format for `" + fxPair.leftPath + "` and `" + fxPair.rightPath + "`");
        }
        
        System.out.println("Parsing `" + fxPair.leftPath + "` and `" + fxPair.rightPath + "`...");
    }
    
    public boolean hasNext() throws IOException {
        boolean hasNext = reader.hasNext();
        
        if (!hasNext) {
            reader.close();
            
            if (++fileCursor >= fastxPairs.length) {
                return false;
            }
            
            setReader(fastxPairs[fileCursor]);
            
            return this.hasNext();
        }
        
        return hasNext;
    }
    
    public PairedReadSegments next() throws FileFormatException, IOException {        
        try {
            return reader.next();
        }
        catch (NoSuchElementException e) {
            reader.close();
            
            if (++fileCursor >= fastxPairs.length) {
                throw new NoSuchElementException();
            }
            
            setReader(fastxPairs[fileCursor]);
            
            return this.next();
        }
    }
}
