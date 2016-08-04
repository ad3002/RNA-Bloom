/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package rnabloom.bloom;

/**
 *
 * @author kmnip
 */
public class UnsafeBitArray extends AbstractLargeBitBuffer {
    
    private final long size;
    private final UnsafeByteArray backingByteArray;
    
    public UnsafeBitArray(long size) throws NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
        this.size = size;
        long numBytes = size / Byte.SIZE;
        if (size % Byte.SIZE > 0) {
            ++numBytes;
        }
        
        backingByteArray = new UnsafeByteArray(numBytes);
    }
    
    @Override
    public void set(long index) {
        long byteIndex = index / Byte.SIZE;
        byte b = backingByteArray.get(byteIndex);
        b |= (1 << (int) (byteIndex % Byte.SIZE));
        backingByteArray.set(byteIndex, b);
    }

    @Override
    public boolean get(long index) {
        long byteIndex = index / Byte.SIZE;
        return (backingByteArray.get(byteIndex) & (1 << (int) (byteIndex % Byte.SIZE))) != 0;
    }
        
    @Override
    public long size() {
        return size;
    }
    
    public void empty() {
        backingByteArray.empty();
    }
        
    @Override
    public long popCount() {
        return backingByteArray.bitPopCount();
    }
    
    public void destroy() {
        backingByteArray.destroy();
    }
}
