package dev.composemc.sync;

import java.io.ByteArrayOutputStream;

/** One bounded message over a reliable, ordered transport. Allocation grows with received data. */
public final class MessageAssembly implements AutoCloseable {
    private final int total;
    private ByteArrayOutputStream bytes;
    private long lastProgress;
    private boolean closed;
    public MessageAssembly(int total,int maximum,long tick){
        if(total<1||total>maximum)throw new IllegalArgumentException("Invalid message bounds");
        this.total=total;lastProgress=tick;bytes=new ByteArrayOutputStream(Math.min(total,4096));
    }
    public byte[] append(int offset,byte[] part,long tick){
        if(closed||offset!=bytes.size()||part.length==0||part.length>total-bytes.size()||tick<lastProgress)throw new SyncException("Invalid message fragment");
        bytes.write(part,0,part.length);lastProgress=tick;
        if(bytes.size()!=total)return null;
        closed=true;
        byte[] result=bytes.toByteArray();bytes=null;return result;
    }
    public boolean expired(long tick,long timeout){return !closed&&tick-lastProgress>timeout;}
    @Override public void close(){closed=true;bytes=null;}
}
