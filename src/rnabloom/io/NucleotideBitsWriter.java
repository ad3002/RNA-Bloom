/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package rnabloom.io;

import java.io.FileOutputStream;
import java.io.IOException;
import static rnabloom.util.SeqBitsUtils.intToFourBytes;
import static rnabloom.util.SeqBitsUtils.seqToBitsParallelized;

/**
 *
 * @author Ka Ming Nip
 */
public class NucleotideBitsWriter {
    private final FileOutputStream out;
    
    public NucleotideBitsWriter(String path, boolean append) throws IOException {
        out = new FileOutputStream(path, append);
    }
    
    public void write(String seq) throws IOException {
        byte[] lenBytes = intToFourBytes(seq.length());
        byte[] bytes = seqToBitsParallelized(seq);
        synchronized(this) {
            out.write(lenBytes);
            out.write(bytes);
        }
    }
    
    public void close() throws IOException {
        out.flush();
        out.close();
    }
}
